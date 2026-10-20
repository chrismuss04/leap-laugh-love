import { Component, DestroyRef, computed, effect, inject, signal, untracked, viewChild } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Subscription, catchError, map, of } from 'rxjs';
import { MarketDataService } from '../../services/market-data';
import { formatMoney } from '../../shared/format';
import { ToolsContext } from '../tools-context';
import { DrawCanvasComponent, LevelChange } from './draw-canvas';
import { DrawHorizon, HORIZONS, stepLabel } from './horizons';
import { TradeIdea, adjustIdea, interpret } from './interpret';
import { DataPoint } from './stroke';

/** Symbols offered when the account holds nothing to draw. */
const FALLBACK_SYMBOLS: readonly string[] = ['AAPL', 'MSFT', 'NVDA', 'TSLA'];

/** What a drawn idea means, in a sentence. */
export function describeIdea(symbol: string, idea: TradeIdea): string {
  const money = formatMoney;
  switch (idea.shape) {
    case 'flat':
      return 'That line stays flat. Draw a move up or down to make a trade.';
    case 'rally':
      return `Buy ${symbol} now. Take profit at ${money(idea.target)}, stop loss at ${money(idea.stop)}.`;
    case 'dip-then-rally':
      return `Buy ${symbol} if it dips to ${money(idea.entry)}. Take profit at ${money(idea.target)}, stop loss at ${money(idea.stop)}.`;
    case 'rally-then-fall':
      return idea.needsShares
        ? `You see ${symbol} peaking at ${money(idea.entry)}, but you'd need to own it to sell.`
        : `Sell ${symbol} if it rises to ${money(idea.entry)}, before it falls back to ${money(idea.target)}.`;
    case 'fall':
      return idea.needsShares
        ? `You see ${symbol} falling to ${money(idea.target)}, but you'd need to own it to sell.`
        : `Sell ${symbol} now, before it falls to ${money(idea.target)}.`;
  }
}

/**
 * Draw Your Trade's workspace: pick a stock and a horizon, then draw where its price goes. The line
 * is read as a trade - its levels drawn over the chart and draggable - and said in a sentence.
 */
@Component({
  selector: 'app-draw-tool',
  imports: [DrawCanvasComponent],
  template: `
    @if (context.loading()) {
      <p class="status" data-testid="draw-loading">Loading your account…</p>
    } @else {
      <div class="bar">
        <div class="symbols" role="radiogroup" aria-label="Stock to draw">
          @for (s of symbols(); track s) {
            <button type="button" role="radio" class="chip" [attr.aria-checked]="s === symbol()"
                    [attr.data-symbol]="s" (click)="pick(s)">{{ s }}</button>
          }
        </div>
        <div class="horizons" role="radiogroup" aria-label="How far ahead">
          @for (h of horizons; track h.id) {
            <button type="button" role="radio" class="seg" [attr.aria-checked]="h.id === horizon().id"
                    [attr.data-horizon]="h.id" (click)="setHorizon(h)">{{ h.label }}</button>
          }
        </div>
      </div>

      <div class="headline">
        <span class="symbol">{{ symbol() }}</span>
        <span class="price num" data-testid="draw-price">{{ money(price()) }}</span>
      </div>

      <div class="stage">
        @if (historyState() === 'error') {
          <p class="status" role="alert">
            Couldn't load {{ symbol() }}'s price history.
            <button type="button" class="link" (click)="reload()">Try again</button>
          </p>
        } @else if (historyState() === 'loading' && !history().length) {
          <span class="skeleton chart-skeleton" aria-hidden="true"></span>
        } @else {
          <app-draw-canvas [symbol]="symbol()" [history]="history()" [price]="price()"
                           [futureSteps]="horizon().futureSteps" [stepLabel]="label()" [idea]="idea()"
                           (drawStart)="idea.set(null)" (drawn)="read($event)" (cleared)="idea.set(null)"
                           (levelChange)="adjust($event)"></app-draw-canvas>
        }
      </div>

      <div class="reading" aria-live="polite" data-testid="draw-reading">
        @if (idea(); as i) {
          <p [attr.data-shape]="i.shape">{{ sentence() }}</p>
          <button type="button" class="link" (click)="canvas()?.clear()">Clear</button>
        } @else {
          <p class="muted">Drag from the glowing dot into the shaded area.</p>
        }
      </div>
    }
  `,
  styles: [`
    :host { display: block; }
    .status { font-size: 14px; color: var(--muted-foreground); }
    .bar { display: flex; flex-wrap: wrap; justify-content: space-between; gap: 12px; }
    .symbols, .horizons { display: flex; flex-wrap: wrap; gap: 6px; }
    .chip, .seg {
      padding: 6px 12px;
      border-radius: 999px;
      border: 1px solid var(--border);
      background: var(--surface);
      color: var(--muted-foreground);
      font-size: 13px;
      font-weight: 600;
      transition: color 0.2s ease, background 0.2s ease, border-color 0.2s ease;
    }
    .chip:hover, .seg:hover { color: var(--foreground); }
    .chip[aria-checked='true'], .seg[aria-checked='true'] {
      color: var(--foreground);
      border-color: color-mix(in srgb, var(--primary) 60%, transparent);
      background: color-mix(in srgb, var(--primary) 12%, var(--surface));
    }
    .headline { display: flex; align-items: baseline; gap: 12px; margin-top: 18px; }
    .headline .symbol { font-size: 15px; font-weight: 600; color: var(--muted-foreground); }
    .headline .price { font-size: 32px; font-weight: 600; color: var(--foreground); }
    .stage { position: relative; margin-top: 8px; min-height: 400px; }
    .chart-skeleton { display: block; height: 400px; border-radius: var(--radius-lg); }
    @media (max-width: 640px) { .stage, .chart-skeleton { min-height: 320px; height: 320px; } }
    .reading { display: flex; align-items: center; justify-content: space-between; gap: 12px; min-height: 40px; margin-top: 8px; font-size: 15px; color: var(--foreground); }
    .reading .muted { color: var(--muted-foreground); font-size: 14px; }
    .link { background: none; color: var(--foreground); font-size: 13px; font-weight: 600; text-decoration: underline; white-space: nowrap; }
  `]
})
export class DrawToolComponent {
  readonly context = inject(ToolsContext);
  private readonly marketData = inject(MarketDataService);
  private readonly destroyRef = inject(DestroyRef);
  readonly canvas = viewChild(DrawCanvasComponent);

  readonly horizons = HORIZONS;
  readonly horizon = signal<DrawHorizon>(HORIZONS[0]);
  /** The symbol picked; null follows the account's largest holding. */
  private readonly picked = signal<string | null>(null);
  readonly history = signal<number[]>([]);
  readonly historyState = signal<'loading' | 'ready' | 'error'>('loading');
  readonly idea = signal<TradeIdea | null>(null);
  private readonly reloads = signal(0);
  private request?: Subscription;

  readonly symbols = computed(() => {
    const held = this.context.holdings().map(h => h.symbol).slice(0, 6);
    return held.length ? held : [...FALLBACK_SYMBOLS];
  });

  readonly symbol = computed(() => this.picked() ?? this.symbols()[0]);

  /** Shares of the symbol the account holds. */
  readonly held = computed(() => this.context.holdings().find(h => h.symbol === this.symbol())?.quantity ?? 0);

  /** The live price, else the last close. */
  readonly price = computed(() => {
    const live = this.context.prices()[this.symbol()];
    return live > 0 ? live : this.history()[this.history().length - 1] ?? null;
  });

  readonly label = computed(() => {
    const interval = this.horizon().interval;
    return (step: number) => stepLabel(step, interval);
  });

  readonly sentence = computed(() => {
    const idea = this.idea();
    return idea ? describeIdea(this.symbol(), idea) : '';
  });

  money = formatMoney;

  constructor() {
    // Keep the drawn symbol on the live price stream.
    effect(() => {
      const symbol = this.symbol();
      untracked(() => this.context.toolSymbols.set([symbol]));
    });
    this.destroyRef.onDestroy(() => this.context.toolSymbols.set([]));

    effect(() => {
      const symbol = this.symbol();
      const horizon = this.horizon();
      this.reloads();
      untracked(() => this.load(symbol, horizon));
    });
  }

  pick(symbol: string): void {
    if (symbol !== this.symbol()) {
      this.picked.set(symbol);
      this.idea.set(null);
    }
  }

  setHorizon(horizon: DrawHorizon): void {
    if (horizon.id !== this.horizon().id) {
      this.horizon.set(horizon);
      this.idea.set(null);
    }
  }

  reload(): void {
    this.reloads.update(n => n + 1);
  }

  read(line: DataPoint[]): void {
    this.idea.set(interpret(line, line[0]?.price, { held: this.held() }));
  }

  adjust(change: LevelChange): void {
    const idea = this.idea();
    if (idea) {
      this.idea.set(adjustIdea(idea, { [change.level]: change.price }));
    }
  }

  private load(symbol: string, horizon: DrawHorizon): void {
    this.request?.unsubscribe();
    this.historyState.set('loading');
    this.history.set([]);
    const to = new Date();
    // Candles are only recorded while the market trades, so reach well back for enough of them.
    const reach = Math.max(horizon.historySteps * horizon.interval * 4, 7 * 86_400) * 1000;
    this.request = this.marketData.getHistory(symbol, new Date(to.getTime() - reach), to, horizon.interval, horizon.historySteps)
      .pipe(
        map(candles => candles.map(c => Number(c.close)).filter(c => Number.isFinite(c) && c > 0)),
        catchError(() => of(null)),
        takeUntilDestroyed(this.destroyRef)
      )
      .subscribe(closes => {
        this.history.set(closes ?? []);
        this.historyState.set(closes ? 'ready' : 'error');
      });
  }
}
