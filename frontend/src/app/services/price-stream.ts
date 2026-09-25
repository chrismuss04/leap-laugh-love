import { Injectable, NgZone, OnDestroy, inject, signal } from '@angular/core';
import { AuthService } from './auth.service';

export type StreamStatus = 'idle' | 'connecting' | 'live' | 'reconnecting';

/**
 * One shared live-price connection to the market data SSE stream.
 *
 * The stream is JWT-secured and the browser's EventSource cannot send an Authorization header,
 * so this reads the stream with fetch() and parses the SSE frames itself. Parsing runs outside
 * Angular's zone and ticks are flushed into the `prices` signal a few times a second, so a
 * 1-second simulation tick across dozens of symbols doesn't run change detection per message.
 */
@Injectable({
  providedIn: 'root'
})
export class PriceStreamService implements OnDestroy {
  private static readonly FLUSH_MS = 400;
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
  private flushTimer?: ReturnType<typeof setTimeout>;
  private pending: Record<string, number> = {};
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
    clearTimeout(this.flushTimer);
    this.flushTimer = undefined;
    this.pending = {};
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
      this.flushTimer ??= setTimeout(() => this.flush(), PriceStreamService.FLUSH_MS);
    } catch {
      // A malformed frame is dropped; the next tick for the symbol replaces it.
    }
  }

  private flush(): void {
    const batch = this.pending;
    this.pending = {};
    this.flushTimer = undefined;
    this.zone.run(() => this.prices.update(current => ({ ...current, ...batch })));
  }

  private scheduleReconnect(): void {
    const delay = Math.min(PriceStreamService.MAX_BACKOFF_MS, 1000 * 2 ** this.attempt);
    this.attempt++;
    this.zone.run(() => this.status.set('reconnecting'));
    this.retryTimer = setTimeout(() => this.connect(), delay);
  }
}
