import { Injectable, NgZone, OnDestroy, inject, signal } from '@angular/core';
import { AuthService } from './auth.service';

export type StreamStatus = 'idle' | 'connecting' | 'live' | 'reconnecting';

/**
 * One shared live-price connection to the market data SSE stream.
 *
 * The stream is JWT-secured and the browser's EventSource cannot send an Authorization header,
 * so this reads the stream with fetch() and parses the SSE frames itself. Parsing runs outside
 * Angular's zone, so a 1-second simulation tick across dozens of symbols doesn't run change
 * detection per message.
 *
 * The simulation ticks every symbol in lockstep, which reads as robotic on screen. Instead each
 * symbol re-prices on its own randomized schedule, and only jumps the queue when it has moved
 * enough to matter - so the board updates a few names at a time, like a real market.
 */
@Injectable({
  providedIn: 'root'
})
export class PriceStreamService implements OnDestroy {
  /** How often buffered ticks are checked for symbols that are due to re-price. */
  private static readonly FLUSH_MS = 250;
  /** A symbol re-prices at a random point in this window after its last update... */
  private static readonly MIN_QUIET_MS = 4_000;
  private static readonly MAX_QUIET_MS = 12_000;
  /** ...unless it has moved at least this fraction since then, */
  private static readonly BIG_MOVE = 0.0005;
  /** ...and even a big move waits this long, so one volatile name can't flicker. */
  private static readonly MIN_GAP_MS = 1_500;
  private static readonly MAX_BACKOFF_MS = 30_000;
  /**
   * Every instrument ticks once a second, so this long without a byte means the connection is
   * dead even though it never closed - e.g. market-data-app restarted and the dev-server proxy
   * left the browser's side open. Without this the header kept saying "Live" over frozen prices.
   */
  private static readonly STALL_MS = 10_000;

  /** Latest live price per symbol. */
  readonly prices = signal<Record<string, number>>({});
  readonly status = signal<StreamStatus>('idle');

  private readonly auth = inject(AuthService);
  private readonly zone = inject(NgZone);

  private symbols = new Set<string>();
  private controller?: AbortController;
  private retryTimer?: ReturnType<typeof setTimeout>;
  private flushTimer?: ReturnType<typeof setInterval>;
  /** Latest unpublished tick per symbol. */
  private pending: Record<string, number> = {};
  private publishedAt: Record<string, number> = {};
  private dueAt: Record<string, number> = {};
  private attempt = 0;

  /** Adds symbols to the stream. Reconnects only if the set actually grew. */
  watch(symbols: Iterable<string>): void {
    let changed = false;
    for (const symbol of symbols) {
      const upper = symbol.toUpperCase();
      if (!this.symbols.has(upper)) {
        this.symbols.add(upper);
        changed = true;
      }
    }
    if (changed) {
      this.attempt = 0;
      this.connect();
    }
  }

  /** Closes the connection and forgets every symbol, e.g. on logout. */
  stop(): void {
    this.symbols.clear();
    this.controller?.abort();
    this.controller = undefined;
    clearTimeout(this.retryTimer);
    clearInterval(this.flushTimer);
    this.flushTimer = undefined;
    this.pending = {};
    this.publishedAt = {};
    this.dueAt = {};
    this.prices.set({});
    this.status.set('idle');
  }

  ngOnDestroy(): void {
    this.stop();
  }

  private connect(): void {
    clearTimeout(this.retryTimer);
    this.controller?.abort();
    const token = this.auth.getToken();
    if (!token || this.symbols.size === 0) {
      return;
    }
    const controller = new AbortController();
    this.controller = controller;
    this.status.set(this.attempt === 0 ? 'connecting' : 'reconnecting');
    const url = `/api/marketdata/stream?symbols=${encodeURIComponent([...this.symbols].join(','))}`;
    this.zone.runOutsideAngular(() => this.read(url, token, controller));
  }

  private async read(url: string, token: string, controller: AbortController): Promise<void> {
    let lastDataAt = Date.now();
    const watchdog = setInterval(() => {
      if (Date.now() - lastDataAt > PriceStreamService.STALL_MS && this.controller === controller) {
        controller.abort();
        this.scheduleReconnect();
      }
    }, 2_000);
    try {
      const response = await fetch(url, {
        headers: { Authorization: `Bearer ${token}`, Accept: 'text/event-stream' },
        signal: controller.signal
      });
      if (response.status === 401) {
        this.zone.run(() => this.stop());
        return;
      }
      if (!response.ok || !response.body) {
        throw new Error(`stream responded ${response.status}`);
      }
      this.zone.run(() => this.status.set('live'));
      this.attempt = 0;

      const reader = response.body.pipeThrough(new TextDecoderStream()).getReader();
      let buffer = '';
      for (;;) {
        const { value, done } = await reader.read();
        if (done) {
          break;
        }
        lastDataAt = Date.now();
        buffer += value;
        const frames = buffer.split(/\r?\n\r?\n/);
        buffer = frames.pop() ?? '';
        frames.forEach(frame => this.handleFrame(frame));
      }
      throw new Error('stream closed');
    } catch {
      if (controller.signal.aborted) {
        return;
      }
      this.scheduleReconnect();
    } finally {
      clearInterval(watchdog);
    }
  }

  private handleFrame(frame: string): void {
    let event = 'message';
    const data: string[] = [];
    for (const line of frame.split(/\r?\n/)) {
      if (line.startsWith('event:')) {
        event = line.slice(6).trim();
      } else if (line.startsWith('data:')) {
        data.push(line.slice(5).trimStart());
      }
    }
    if (event !== 'price' || data.length === 0) {
      return;
    }
    try {
      const tick = JSON.parse(data.join('\n')) as { symbol: string; price: number };
      this.pending[tick.symbol] = Number(tick.price);
      this.flushTimer ??= setInterval(() => this.flush(), PriceStreamService.FLUSH_MS);
    } catch {
      // A malformed frame is dropped; the next tick for the symbol replaces it.
    }
  }

  /** Publishes each buffered tick whose symbol is due, has moved enough, or has never been shown. */
  private flush(): void {
    const now = Date.now();
    const shown = this.prices();
    const batch: Record<string, number> = {};
    for (const [symbol, price] of Object.entries(this.pending)) {
      const last = shown[symbol];
      const bigMove = last !== undefined && last !== 0
        && Math.abs(price - last) / Math.abs(last) >= PriceStreamService.BIG_MOVE
        && now - (this.publishedAt[symbol] ?? 0) >= PriceStreamService.MIN_GAP_MS;
      if (last === undefined || bigMove || now >= (this.dueAt[symbol] ?? 0)) {
        batch[symbol] = price;
        delete this.pending[symbol];
        this.publishedAt[symbol] = now;
        this.dueAt[symbol] = now + PriceStreamService.MIN_QUIET_MS
          + Math.random() * (PriceStreamService.MAX_QUIET_MS - PriceStreamService.MIN_QUIET_MS);
      }
    }
    if (Object.keys(batch).length) {
      this.zone.run(() => this.prices.update(current => ({ ...current, ...batch })));
    }
  }

  private scheduleReconnect(): void {
    const delay = Math.min(PriceStreamService.MAX_BACKOFF_MS, 1000 * 2 ** this.attempt);
    this.attempt++;
    this.zone.run(() => this.status.set('reconnecting'));
    this.retryTimer = setTimeout(() => this.connect(), delay);
  }
}
