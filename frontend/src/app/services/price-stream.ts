import { Injectable, OnDestroy, signal } from '@angular/core';
import { EventStream } from './event-stream';
import { parseSseFrame } from './sse-frame';

/**
 * One shared live-price connection to the market data SSE stream.
 *
 * Parsing runs outside Angular's zone, so a 1-second simulation tick across dozens of symbols
 * doesn't run change detection per message.
 *
 * The simulation ticks every symbol in lockstep, which reads as robotic on screen. Instead each
 * symbol re-prices on its own randomized schedule, and only jumps the queue when it has moved
 * enough to matter - so the board updates a few names at a time, like a real market.
 */
@Injectable({
  providedIn: 'root'
})
export class PriceStreamService extends EventStream implements OnDestroy {
  /** How often buffered ticks are checked for symbols that are due to re-price. */
  private static readonly FLUSH_MS = 250;
  /** A symbol re-prices at a random point in this window after its last update... */
  private static readonly MIN_QUIET_MS = 4_000;
  private static readonly MAX_QUIET_MS = 12_000;
  /** ...unless it has moved at least this fraction since then, */
  private static readonly BIG_MOVE = 0.0005;
  /** ...and even a big move waits this long, so one volatile name can't flicker. */
  private static readonly MIN_GAP_MS = 1_500;
  /**
   * Every instrument ticks once a second, so this long without a byte means the connection is
   * dead even though it never closed - e.g. market-data-app restarted and the dev-server proxy
   * left the browser's side open. Without this the header kept saying "Live" over frozen prices.
   */
  protected readonly stallMs = 10_000;
  protected readonly stallCheckMs = 2_000;

  /** Latest live price per symbol. */
  readonly prices = signal<Record<string, number>>({});

  private symbols = new Set<string>();
  private flushTimer?: ReturnType<typeof setInterval>;
  /** Latest unpublished tick per symbol. */
  private pending: Record<string, number> = {};
  private publishedAt: Record<string, number> = {};
  private dueAt: Record<string, number> = {};

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
    this.disconnect();
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

  protected streamUrl(): string | null {
    if (this.symbols.size === 0) {
      return null;
    }
    return `/api/marketdata/stream?symbols=${encodeURIComponent([...this.symbols].join(','))}`;
  }

  protected handleFrame(frame: string): void {
    const parsed = parseSseFrame(frame);
    if (parsed?.event !== 'price') {
      return;
    }
    try {
      const tick = JSON.parse(parsed.data) as { symbol: string; price: number };
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
      const movedFar = last !== undefined && last !== 0
        && Math.abs(price - last) / Math.abs(last) >= PriceStreamService.BIG_MOVE;
      const bigMove = movedFar && now - (this.publishedAt[symbol] ?? 0) >= PriceStreamService.MIN_GAP_MS;
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
}
