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
 *
 * A total over many symbols would still change every time any one of them did, several times a
 * second for a big portfolio. Totals read `settledPrices` instead, which catches up with `prices`
 * at most once per settle interval.
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
  /** How often totals over many symbols may change. */
  private static readonly SETTLE_MS = 5_000;
  /**
   * Every instrument ticks once a second, so this long without a byte means the connection is
   * dead even though it never closed - e.g. market-data-app restarted and the dev-server proxy
   * left the browser's side open. Without this the header kept saying "Live" over frozen prices.
   */
  protected readonly stallMs = 10_000;
  protected readonly stallCheckMs = 2_000;

  /** Latest live price per symbol. */
  readonly prices = signal<Record<string, number>>({});
  /** `prices` as of the last settle: what account and portfolio totals are valued at. */
  readonly settledPrices = signal<Record<string, number>>({});

  /** The symbols each view asked for, so one view leaving drops only what no other still needs. */
  private readonly owners = new Map<string, Set<string>>();
  private symbols = new Set<string>();
  private flushTimer?: ReturnType<typeof setInterval>;
  /** Latest unpublished tick per symbol. */
  private pending: Record<string, number> = {};
  private publishedAt: Record<string, number> = {};
  private dueAt: Record<string, number> = {};
  private settledAt = 0;

  /**
   * Adds symbols to the stream on behalf of `owner`. Reconnects only if the set actually grew.
   * Views that stream symbols only while they're open pass their own owner and release it when
   * they close; everything else shares one owner that lasts until `stop()`.
   */
  watch(symbols: Iterable<string>, owner = 'shared'): void {
    const wanted = this.owners.get(owner) ?? new Set<string>();
    for (const symbol of symbols) {
      wanted.add(symbol.toUpperCase());
    }
    this.owners.set(owner, wanted);
    this.resubscribe();
  }

  /** Drops an owner's symbols, keeping any another owner still watches. */
  release(owner: string): void {
    if (this.owners.delete(owner)) {
      this.resubscribe();
    }
  }

  /** Reconnects with the symbols every owner wants together, if that set changed. */
  private resubscribe(): void {
    const next = new Set([...this.owners.values()].flatMap(wanted => [...wanted]));
    const changed = next.size !== this.symbols.size || [...next].some(symbol => !this.symbols.has(symbol));
    this.symbols = next;
    if (!changed) {
      return;
    }
    if (next.size === 0) {
      this.disconnect();
      this.status.set('idle');
      return;
    }
    this.attempt = 0;
    this.connect();
  }

  /** Closes the connection and forgets every symbol, e.g. on logout. */
  stop(): void {
    this.owners.clear();
    this.symbols.clear();
    this.disconnect();
    clearInterval(this.flushTimer);
    this.flushTimer = undefined;
    this.pending = {};
    this.publishedAt = {};
    this.dueAt = {};
    this.settledAt = 0;
    this.prices.set({});
    this.settledPrices.set({});
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
      this.zone.run(() => {
        this.prices.update(current => ({ ...current, ...batch }));
        // A symbol's first price settles at once, so a new holding is valued straight away.
        const settled = this.settledPrices();
        if (now - this.settledAt >= PriceStreamService.SETTLE_MS || Object.keys(batch).some(s => !(s in settled))) {
          this.settledAt = now;
          this.settledPrices.set(this.prices());
        }
      });
    }
  }
}
