import { Component, DestroyRef, computed, effect, inject, signal, untracked, viewChild } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Subscription, catchError, map, of } from 'rxjs';
import { SymbolSearchComponent } from '../../dashboard/symbol-search/symbol-search';
import { MarketDataService } from '../../services/market-data';
import { MarketStatusService } from '../../services/market-status';
import { formatMoney, formatSignedMoney } from '../../shared/format';
import { OrderLine, pickConditions } from '../../shared/order-plan';
import { RollingNumberComponent } from '../../shared/rolling-number';
import { ToolsContext } from '../tools-context';
import { conditionsLabel } from '../trade-plan';
import { DrawCanvasComponent, LevelChange } from './draw-canvas';
import { CONE_QUANTILES, boldness, cone, hitsFirstProbability, touchProbability, volatility } from './forecast';
import { DrawHorizon, HORIZONS, stepLabel } from './horizons';
import { OddsRingComponent } from './odds-ring';
import { PRESETS, Preset, presetIcon, presetLine } from './presets';
import { TradeIdea, adjustIdea, interpret, payoff, toOrderLine } from './interpret';
import { DataPoint } from './stroke';
import { SHAPE_NAMES, defaultShares, maxShares, riskRewardSplit, sentenceText, tradeSentence } from './trade-summary';

/** Symbols offered when the account holds nothing to draw. */
const FALLBACK_SYMBOLS: readonly string[] = ['AAPL', 'MSFT', 'NVDA', 'TSLA'];

/** One chance in the odds readout. */
export interface Chance {
  key: 'fills' | 'reaches' | 'first';
  value: number;
  label: string;
  tone: 'gain' | 'loss' | 'entry' | 'info';
}

const BOLDNESS: Record<'typical' | 'bold' | 'moonshot', string> = {
  typical: 'A typical move',
  bold: 'A bold call',
  moonshot: 'A moonshot'
};

/** Quick sizes, as fractions of the most shares the trade can be. */
export const SIZE_STEPS: readonly { label: string; share: number }[] = [
  { label: '25%', share: 0.25 }, { label: '50%', share: 0.5 }, { label: '75%', share: 0.75 }, { label: 'Max', share: 1 }
];

/** An order's identity in the tray: everything that's sent, so a changed level counts as a change. */
const orderKey = (o: OrderLine) =>
  JSON.stringify([o.symbol, o.side, o.quantity, pickConditions(o)]);

/**
 * Draw Your Trade's workspace: pick a stock and a horizon, then draw where its price goes. The line
 * is read as a trade - its levels drawn over the chart and draggable - and laid out underneath:
 * what it does in a sentence, a size, what it makes against what it risks, and Add to plan, which
 * puts it in the tray tagged Draw (replacing an earlier drawing's order).
 */
@Component({
  selector: 'app-draw-tool',
  imports: [DrawCanvasComponent, SymbolSearchComponent, RollingNumberComponent, OddsRingComponent],
  template: `
    @if (context.loading()) {
      <p class="status" data-testid="draw-loading">Loading your account…</p>
    } @else {
      <div class="bar">
        <app-symbol-search class="search" [livePrices]="context.prices()" [shortcut]="false"
                           placeholder="Search any stock" (picked)="pick($event.symbol)"></app-symbol-search>
        <div class="horizons" role="radiogroup" aria-label="How far ahead">
          @for (h of horizons; track h.id) {
            <button type="button" role="radio" class="seg" [attr.aria-checked]="h.id === horizon().id"
                    [attr.data-horizon]="h.id" (click)="setHorizon(h)">{{ h.label }}</button>
          }
        </div>
      </div>

      <div class="symbols" role="radiogroup" aria-label="Stock to draw">
        @for (s of symbols(); track s) {
          <button type="button" role="radio" class="chip" [attr.aria-checked]="s === symbol()"
                  [attr.data-symbol]="s" (click)="pick(s)">{{ s }}</button>
        }
      </div>

      <div class="headline">
        <span class="symbol">{{ symbol() }}</span>
        <span class="price num" data-testid="draw-price" [appRollingNumber]="price()"></span>
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
                           [futureSteps]="horizon().futureSteps" [stepLabel]="label()" [idea]="liveIdea()" [cone]="forecast()"
                           (drawStart)="idea.set(null)" (drawn)="read($event)" (cleared)="idea.set(null)"
                           (levelChange)="adjust($event)"></app-draw-canvas>
        }
      </div>

      @if (historyState() === 'ready') {
        <div class="presets" role="group" aria-label="Or start from a shape">
          <span class="presets-label">Or try a shape</span>
          @for (p of presets; track p.id) {
            <button type="button" class="preset" [attr.data-preset]="p.id" [title]="p.hint" (click)="usePreset(p)">
              <svg width="28" height="14" viewBox="0 0 28 14" aria-hidden="true"><path [attr.d]="icons[p.id]" /></svg>
              {{ p.label }}
            </button>
          }
        </div>
      }

      <div class="sr-only" aria-live="polite">{{ spoken() }}</div>

      @if (liveIdea(); as i) {
        <section class="trade" [attr.data-side]="i.side" [attr.data-shape]="i.shape" data-testid="draw-trade"
                 aria-label="Your trade">
          <header>
            <span class="badge">{{ shapeName() }}</span>
            @if (terms()) { <span class="terms" data-testid="draw-terms">{{ terms() }}</span> }
            <button type="button" class="link quiet" (click)="canvas()?.clear()">Redraw</button>
          </header>

          <p class="sentence" data-testid="draw-sentence">
            @for (part of sentence(); track $index) {
              <span [attr.data-tone]="part.tone ?? null">{{ part.text }}</span>
            }
          </p>

          @if (odds(); as o) {
            <div class="odds" data-testid="draw-odds">
              <div class="rings">
                @for (c of o.chances; track c.key) {
                  <app-odds-ring [value]="c.value" [label]="c.label" [tone]="c.tone" [attr.data-chance]="c.key"></app-odds-ring>
                }
              </div>
              <div class="odds-side">
                <span class="bold-pill" [attr.data-boldness]="o.boldness" data-testid="draw-boldness">{{ o.boldnessLabel }} <span class="num">· {{ o.sigmas }}σ</span></span>
                <span class="legend">
                  <span class="swatch inner" aria-hidden="true"></span>50%
                  <span class="swatch outer" aria-hidden="true"></span>90% of paths
                </span>
                <span class="footnote">
                  Odds of a random walk at {{ symbol() }}'s recent volatility, not a prediction.
                </span>
              </div>
            </div>
          }

          @if (tradeable()) {
            <div class="size">
              <div class="size-head">
                <label for="draw-shares">Shares</label>
                <span class="num size-value" data-testid="draw-size">
                  <b>{{ shares() }}</b> · {{ money(cost()) }}
                  <span class="of">of {{ i.side === 'BUY' ? money(cash()) + ' cash' : held() + ' held' }}</span>
                </span>
              </div>
              <input id="draw-shares" type="range" min="1" [max]="max()" step="1" [value]="shares()"
                     [style.--fill]="fill() + '%'" (input)="setShares($any($event.target).valueAsNumber)"
                     data-testid="draw-shares" />
              <div class="steps" role="group" aria-label="Quick sizes">
                @for (s of sizeSteps; track s.label) {
                  <button type="button" class="step" [attr.aria-pressed]="shares() === sized(s.share)"
                          (click)="setShares(sized(s.share))">{{ s.label }}</button>
                }
              </div>
            </div>

            @if (i.side === 'BUY') {
              <div class="rr" data-testid="draw-risk-reward">
                <div class="rr-bar" aria-hidden="true">
                  <span class="rr-risk" [style.flex-grow]="split().risk"></span>
                  <span class="rr-mid"></span>
                  <span class="rr-reward" [style.flex-grow]="split().reward"></span>
                </div>
                <div class="rr-figures">
                  <span class="risk">Risk <b class="num" [appRollingNumber]="-pay().risk" [format]="signed"></b></span>
                  <span class="ratio num" data-testid="draw-ratio">{{ ratio() }}</span>
                  <span class="reward">Reward <b class="num" [appRollingNumber]="pay().reward" [format]="signed"></b></span>
                </div>
              </div>
            } @else {
              <div class="rr sell">
                <span>Raises about <b class="num" [appRollingNumber]="cost()"></b></span>
                <span>Dodges <b class="num loss" [appRollingNumber]="pay().reward"></b> if you're right</span>
              </div>
            }

            @if (notice(); as n) { <p class="notice" data-testid="draw-notice">{{ n }}</p> }

            <footer>
              <button #add type="button" class="primary" data-testid="draw-add" [disabled]="!line() || inPlan()"
                      (click)="addToPlan(add)">
                {{ inPlan() ? 'In your plan ✓' : hasDrawOrder() ? 'Update plan' : 'Add to plan' }}
              </button>
            </footer>
          } @else if (!i.needsShares && i.shape !== 'flat') {
            <p class="notice" data-testid="draw-notice">Not enough cash for 1 share at {{ money(i.entry) }}.</p>
          }
        </section>
      } @else {
        <p class="prompt">Drag from the glowing dot into the shaded area. Up means buy, down means sell.</p>
      }
    }
  `,
  styles: [`
    :host { display: block; }
    .status { font-size: 14px; color: var(--muted-foreground); }
    .bar { display: flex; flex-wrap: wrap; align-items: center; justify-content: space-between; gap: 12px; }
    .search { flex: 1 1 260px; max-width: 360px; }
    .symbols, .horizons, .steps { display: flex; flex-wrap: wrap; gap: 6px; }
    .symbols { margin-top: 12px; }
    .chip, .seg, .step {
      padding: 6px 12px;
      border-radius: 999px;
      border: 1px solid var(--border);
      background: var(--surface);
      color: var(--muted-foreground);
      font-size: 13px;
      font-weight: 600;
      transition: color 0.2s ease, background 0.2s ease, border-color 0.2s ease;
    }
    .chip:hover, .seg:hover, .step:hover { color: var(--foreground); }
    .chip[aria-checked='true'], .seg[aria-checked='true'], .step[aria-pressed='true'] {
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
    .presets { display: flex; flex-wrap: wrap; align-items: center; gap: 6px; margin-top: 10px; }
    .presets-label { margin-right: 4px; font-size: 12px; color: var(--muted-foreground); }
    .preset {
      display: inline-flex;
      align-items: center;
      gap: 6px;
      padding: 5px 12px 5px 8px;
      border-radius: 999px;
      border: 1px dashed color-mix(in srgb, var(--primary) 35%, var(--border));
      background: transparent;
      color: var(--muted-foreground);
      font-size: 12px;
      font-weight: 600;
      transition: color 0.2s ease, border-color 0.2s ease, background 0.2s ease;
    }
    .preset:hover, .preset:focus-visible {
      color: var(--foreground);
      border-style: solid;
      border-color: color-mix(in srgb, var(--primary) 60%, transparent);
      background: color-mix(in srgb, var(--primary) 8%, transparent);
    }
    .preset path { fill: none; stroke: var(--primary); stroke-width: 1.75; stroke-linecap: round; stroke-linejoin: round; }
    .prompt { margin-top: 12px; font-size: 14px; color: var(--muted-foreground); text-align: center; }
    .link { background: none; color: var(--foreground); font-size: 13px; font-weight: 600; text-decoration: underline; white-space: nowrap; }
    .link.quiet { margin-left: auto; color: var(--muted-foreground); }

    .trade {
      --accent: var(--gain);
      margin-top: 14px;
      padding: 18px 20px 20px;
      border-radius: var(--radius-lg);
      border: 1px solid color-mix(in srgb, var(--accent) 30%, var(--border));
      background:
        radial-gradient(120% 140% at 0% 0%, color-mix(in srgb, var(--accent) 10%, transparent), transparent 60%),
        var(--card);
      display: flex;
      flex-direction: column;
      gap: 16px;
      animation: rise 0.45s cubic-bezier(0.2, 0.9, 0.3, 1.1) both;
    }
    .trade[data-side='SELL'] { --accent: var(--loss); }
    .trade[data-shape='flat'] { --accent: var(--muted-foreground); }
    header { display: flex; flex-wrap: wrap; align-items: center; gap: 8px 12px; }
    .badge {
      padding: 4px 10px;
      border-radius: 999px;
      background: color-mix(in srgb, var(--accent) 16%, transparent);
      color: var(--accent);
      font-size: 12px;
      font-weight: 700;
      letter-spacing: 0.02em;
      text-transform: uppercase;
    }
    .terms { font-size: 12px; color: var(--muted-foreground); }
    .sentence { margin: 0; font-size: 19px; line-height: 1.5; color: var(--foreground); }
    .sentence [data-tone] { font-weight: 650; font-variant-numeric: tabular-nums; }
    .sentence [data-tone='gain'] { color: var(--gain); }
    .sentence [data-tone='loss'] { color: var(--loss); }

    .odds {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: 16px 28px;
      padding: 14px 16px;
      border-radius: var(--radius-md);
      background: color-mix(in srgb, var(--info) 6%, var(--surface));
      border: 1px solid color-mix(in srgb, var(--info) 18%, var(--border));
    }
    .rings { display: flex; gap: 20px; flex-wrap: wrap; }
    .odds-side { display: flex; flex-direction: column; gap: 8px; flex: 1 1 200px; min-width: 0; }
    .bold-pill {
      align-self: flex-start;
      padding: 4px 10px;
      border-radius: 999px;
      font-size: 13px;
      font-weight: 650;
      background: var(--muted);
      color: var(--foreground);
    }
    .bold-pill[data-boldness='bold'] { background: var(--warning-muted); color: var(--warning); }
    .bold-pill[data-boldness='moonshot'] {
      background: linear-gradient(90deg, color-mix(in srgb, #a78bfa 30%, transparent), color-mix(in srgb, #f472b6 30%, transparent));
      color: #f5d0fe;
    }
    .legend { display: flex; align-items: center; gap: 6px; font-size: 12px; color: var(--muted-foreground); }
    .swatch { width: 14px; height: 8px; border-radius: 3px; background: var(--info); }
    .swatch.inner { opacity: 0.45; }
    .swatch.outer { opacity: 0.2; margin-left: 6px; }
    .footnote { font-size: 12px; color: var(--muted-foreground); }

    .size { display: flex; flex-direction: column; gap: 10px; }
    .size-head { display: flex; justify-content: space-between; align-items: baseline; gap: 12px; font-size: 13px; color: var(--muted-foreground); }
    .size-value { color: var(--foreground); }
    .size-value b { font-size: 16px; }
    .of { color: var(--muted-foreground); }
    input[type='range'] {
      --fill: 50%;
      appearance: none;
      width: 100%;
      height: 6px;
      border-radius: 999px;
      background: linear-gradient(90deg, var(--accent) var(--fill), var(--muted) var(--fill));
      outline: none;
      cursor: pointer;
    }
    input[type='range']::-webkit-slider-thumb {
      appearance: none;
      width: 20px;
      height: 20px;
      border-radius: 50%;
      background: var(--foreground);
      border: 3px solid var(--accent);
      box-shadow: 0 0 0 6px color-mix(in srgb, var(--accent) 18%, transparent);
      transition: transform 0.15s ease;
    }
    input[type='range']::-moz-range-thumb {
      width: 16px;
      height: 16px;
      border-radius: 50%;
      background: var(--foreground);
      border: 3px solid var(--accent);
    }
    input[type='range']:active::-webkit-slider-thumb { transform: scale(1.15); }
    input[type='range']:focus-visible::-webkit-slider-thumb { box-shadow: 0 0 0 6px color-mix(in srgb, var(--ring) 45%, transparent); }
    .step { padding: 4px 10px; font-size: 12px; }

    .rr { display: flex; flex-direction: column; gap: 8px; }
    .rr-bar { display: flex; align-items: center; height: 10px; gap: 3px; }
    .rr-bar span { display: block; height: 100%; border-radius: 999px; transition: flex-grow 0.5s cubic-bezier(0.2, 0, 0, 1); min-width: 6px; }
    .rr-risk { background: linear-gradient(90deg, color-mix(in srgb, var(--loss) 40%, transparent), var(--loss)); }
    .rr-reward { background: linear-gradient(90deg, var(--gain), color-mix(in srgb, var(--gain) 40%, transparent)); }
    .rr-bar .rr-mid { flex: 0 0 3px; height: 18px; min-width: 3px; background: var(--foreground); }
    .rr-figures { display: flex; justify-content: space-between; align-items: baseline; font-size: 13px; color: var(--muted-foreground); }
    .rr-figures b { margin-left: 4px; font-size: 15px; }
    .risk b { color: var(--loss); }
    .reward b { color: var(--gain); }
    .ratio { padding: 2px 8px; border-radius: 999px; background: var(--muted); color: var(--foreground); font-weight: 600; }
    .rr.sell { flex-direction: row; flex-wrap: wrap; justify-content: space-between; gap: 8px; font-size: 13px; color: var(--muted-foreground); }
    .rr.sell b { color: var(--foreground); }
    .rr.sell b.loss { color: var(--loss); }

    .notice { margin: 0; padding: 8px 12px; border-radius: var(--radius-md); background: var(--info-muted); color: var(--info); font-size: 13px; }
    footer { display: flex; justify-content: flex-end; }
    .primary {
      padding: 10px 22px;
      border-radius: 999px;
      background: var(--primary);
      color: var(--primary-foreground);
      font-size: 14px;
      font-weight: 650;
      box-shadow: 0 6px 24px color-mix(in srgb, var(--primary) 35%, transparent);
      transition: transform 0.15s ease, box-shadow 0.2s ease, opacity 0.2s ease;
    }
    .primary:hover:not(:disabled) { transform: translateY(-1px); box-shadow: 0 10px 28px color-mix(in srgb, var(--primary) 45%, transparent); }
    .primary:disabled { opacity: 0.55; cursor: default; box-shadow: none; }

    @keyframes rise { from { opacity: 0; transform: translateY(12px); } to { opacity: 1; transform: none; } }
    @media (prefers-reduced-motion: reduce) {
      .trade { animation: none; }
      .rr-bar span { transition: none; }
    }
    @media (max-width: 640px) {
      .trade { padding: 16px; }
      .sentence { font-size: 17px; }
      .search { max-width: none; }
    }
  `]
})
export class DrawToolComponent {
  readonly context = inject(ToolsContext);
  private readonly marketData = inject(MarketDataService);
  private readonly market = inject(MarketStatusService);
  private readonly destroyRef = inject(DestroyRef);
  readonly canvas = viewChild(DrawCanvasComponent);

  readonly horizons = HORIZONS;
  readonly sizeSteps = SIZE_STEPS;
  readonly presets = PRESETS;
  readonly icons = Object.fromEntries(PRESETS.map(p => [p.id, presetIcon(p)])) as Record<string, string>;
  readonly horizon = signal<DrawHorizon>(HORIZONS[0]);
  /** The symbol picked; null follows the account's largest holding. */
  private readonly picked = signal<string | null>(null);
  readonly history = signal<number[]>([]);
  readonly historyState = signal<'loading' | 'ready' | 'error'>('loading');
  /** The trade read off the line, with any levels the client has dragged. */
  readonly idea = signal<TradeIdea | null>(null);
  /** Shares chosen; null takes the default for the trade. */
  private readonly chosen = signal<number | null>(null);
  private readonly reloads = signal(0);
  private request?: Subscription;

  readonly symbols = computed(() => {
    const held = this.context.holdings().map(h => h.symbol).slice(0, 6);
    const base = held.length ? held : [...FALLBACK_SYMBOLS];
    const picked = this.picked();
    return picked && !base.includes(picked) ? [picked, ...base] : base;
  });

  readonly symbol = computed(() => this.picked() ?? this.symbols()[0]);

  /** Shares of the symbol the account holds. */
  readonly held = computed(() => this.context.holdings().find(h => h.symbol === this.symbol())?.quantity ?? 0);
  readonly cash = computed(() => this.context.account()?.cash ?? 0);

  /** The live price, else the last close. */
  readonly price = computed(() => {
    const live = this.context.prices()[this.symbol()];
    return live > 0 ? live : this.history()[this.history().length - 1] ?? null;
  });

  readonly label = computed(() => {
    const interval = this.horizon().interval;
    return (step: number) => stepLabel(step, interval);
  });

  /** The idea at today's price: a trade made now is made at the live price, not the one drawn from. */
  readonly liveIdea = computed(() => {
    const idea = this.idea();
    const price = this.price();
    return idea && idea.entryType === 'MARKET' && price && idea.shape !== 'flat' ? adjustIdea(idea, { entry: price }) : idea;
  });

  readonly max = computed(() => {
    const idea = this.liveIdea();
    return idea ? maxShares(idea, this.cash(), this.held()) : 0;
  });

  /** Whether the trade can be placed at all: drawn, not flat, and at least a share. */
  readonly tradeable = computed(() => {
    const idea = this.liveIdea();
    return !!idea && idea.shape !== 'flat' && !idea.needsShares && this.max() >= 1;
  });

  readonly shares = computed(() => {
    const idea = this.liveIdea();
    if (!idea || this.max() < 1) {
      return 0;
    }
    const chosen = this.chosen() ?? defaultShares(idea, this.cash(), this.held());
    return Math.min(Math.max(chosen, 1), this.max());
  });

  readonly fill = computed(() => {
    const max = this.max();
    return max > 1 ? ((this.shares() - 1) / (max - 1)) * 100 : 100;
  });

  readonly cost = computed(() => Math.round(this.shares() * (this.liveIdea()?.entry ?? 0) * 100) / 100);
  readonly pay = computed(() => {
    const idea = this.liveIdea();
    return idea ? payoff(idea, this.shares()) : { reward: 0, risk: 0, ratio: null };
  });
  readonly split = computed(() => riskRewardSplit(this.pay()));
  readonly ratio = computed(() => {
    const ratio = this.pay().ratio;
    return ratio === null ? '—' : `${ratio >= 10 ? Math.round(ratio) : ratio.toFixed(1)} : 1`;
  });

  readonly shapeName = computed(() => {
    const idea = this.liveIdea();
    return idea ? SHAPE_NAMES[idea.shape] : '';
  });

  readonly sentence = computed(() => {
    const idea = this.liveIdea();
    return idea ? tradeSentence(this.symbol(), idea, this.shares(), this.pay()) : [];
  });

  /** The trade as the order it puts in the plan, or null when there's nothing to place. */
  readonly line = computed(() => {
    const idea = this.liveIdea();
    return idea && this.tradeable() ? toOrderLine(this.symbol(), idea, this.shares(), this.horizon().timeInForce) : null;
  });

  readonly terms = computed(() => {
    const line = this.line();
    return line ? conditionsLabel(line) : '';
  });

  private readonly drawOrders = computed(() => this.context.plan.orders().filter(o => o.source === 'draw'));
  readonly hasDrawOrder = computed(() => this.drawOrders().length > 0);

  /** Whether the tray already holds exactly this trade. */
  readonly inPlan = computed(() => {
    const line = this.line();
    const orders = this.drawOrders();
    return !!line && orders.length === 1 && orderKey(orders[0]) === orderKey(line);
  });

  /** When an order made now would have to wait. */
  readonly notice = computed(() => {
    const session = this.market.session();
    if (session === null || session === 'REGULAR') {
      return null;
    }
    return this.liveIdea()?.entryType === 'MARKET'
      ? 'The market isn\'t open right now, so this order waits for the next open and fills at that price.'
      : 'The market isn\'t open right now, so this order starts working at the next open.';
  });

  /** Volatility per candle, from the history shown. */
  readonly sigma = computed(() => volatility(this.history()));

  /** Where a random walk could take the price over the horizon, once there's a line to compare. */
  readonly forecast = computed(() => {
    const price = this.price();
    const steps = this.horizon().futureSteps;
    if (!this.idea() || !price) {
      return null;
    }
    return cone(price, this.sigma(), steps, CONE_QUANTILES, Math.max(steps / 48, 0.5));
  });

  /**
   * The line's chances on that walk: that a limit fills, that the price reaches where the line ends
   * up by the time it gets there, and which of a bracket's exits fills first - with how bold a call
   * the move is.
   */
  readonly odds = computed(() => {
    const idea = this.liveIdea();
    const price = this.price();
    if (!idea || !price || idea.shape === 'flat' || idea.needsShares) {
      return null;
    }
    const sigma = this.sigma();
    const when = this.label();
    const chances: Chance[] = [];
    if (idea.entryType === 'LIMIT') {
      chances.push({
        key: 'fills', tone: 'entry',
        value: touchProbability(price, idea.entry, sigma, Math.max(idea.entryStep, 1)),
        label: `${idea.side === 'BUY' ? 'Dips' : 'Rises'} to ${formatMoney(idea.entry)} by ${when(idea.entryStep)}`
      });
    }
    chances.push({
      key: 'reaches', tone: idea.side === 'BUY' ? 'gain' : 'loss',
      value: touchProbability(price, idea.target, sigma, Math.max(idea.targetStep, 1)),
      label: `${idea.target >= price ? 'Reaches' : 'Falls to'} ${formatMoney(idea.target)} by ${when(idea.targetStep)}`
    });
    if (idea.side === 'BUY' && idea.stop !== null) {
      chances.push({
        key: 'first', tone: 'info',
        value: hitsFirstProbability(idea.entry, idea.target, idea.stop),
        label: 'Take profit fills before stop loss'
      });
    }
    const bold = boldness(price, idea.target, sigma, Math.max(idea.targetStep, 1));
    return { chances, boldness: bold.label, boldnessLabel: BOLDNESS[bold.label], sigmas: bold.sigmas.toFixed(1) };
  });

  /** The trade, read out when it changes. */
  readonly spoken = computed(() => sentenceText(this.sentence()));

  money = formatMoney;
  signed = formatSignedMoney;

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

  /** A new line: read its trade, sized afresh. */
  read(line: DataPoint[]): void {
    this.chosen.set(null);
    this.idea.set(interpret(line, line[0]?.price, { held: this.held() }));
  }

  adjust(change: LevelChange): void {
    const idea = this.idea();
    if (idea) {
      this.idea.set(adjustIdea(idea, { [change.level]: change.price }));
    }
  }

  setShares(value: number): void {
    if (Number.isFinite(value)) {
      this.chosen.set(Math.round(value));
    }
  }

  /** Shares for a fraction of the most the trade can be, at least one. */
  sized(share: number): number {
    return Math.max(1, Math.floor(this.max() * share));
  }

  /** Puts a ready-made shape on the chart, sized to this stock's moves, as if drawn. */
  usePreset(preset: Preset): void {
    const price = this.price();
    if (price) {
      this.canvas()?.place(presetLine(preset, price, this.horizon().futureSteps, this.sigma()));
    }
  }

  /**
   * Puts the trade in the tray tagged Draw, replacing an earlier drawing's order, and flies a chip
   * of it from the button down into the tray.
   */
  addToPlan(from?: HTMLElement): void {
    const account = this.context.account();
    const line = this.line();
    if (account && line) {
      this.context.plan.addPlan('draw', account.accountId, [line]);
      if (from) {
        this.fly(from, `${line.side === 'BUY' ? 'Buy' : 'Sell'} ${line.quantity} ${line.symbol}`, line.side);
      }
    }
  }

  /**
   * A chip with the line's thumbnail arcs from `from` into the tray's badge and fades. It's built
   * on the page's body, above everything, so its styles are inline. Skipped under reduced motion.
   */
  private fly(from: HTMLElement, label: string, side: 'BUY' | 'SELL'): void {
    const target = document.querySelector('[data-testid="tray-toggle"] .badge') ?? document.querySelector('[data-testid="tray-toggle"]');
    if (!target || typeof from.animate !== 'function' || matchMedia('(prefers-reduced-motion: reduce)').matches) {
      return;
    }
    const tone = side === 'BUY' ? 'var(--gain)' : 'var(--loss)';
    const chip = document.createElement('div');
    chip.setAttribute('aria-hidden', 'true');
    chip.dataset['testid'] = 'draw-fly';
    Object.assign(chip.style, {
      position: 'fixed', left: '0', top: '0', zIndex: '60', display: 'flex', alignItems: 'center', gap: '8px',
      padding: '6px 12px 6px 8px', borderRadius: '999px', background: 'var(--card)', color: 'var(--foreground)',
      border: `1px solid ${tone}`, boxShadow: `0 8px 28px color-mix(in srgb, ${tone} 35%, transparent)`,
      fontSize: '13px', fontWeight: '600', lineHeight: '1', whiteSpace: 'nowrap', pointerEvents: 'none'
    });
    const ns = 'http://www.w3.org/2000/svg';
    const svg = document.createElementNS(ns, 'svg');
    svg.setAttribute('width', '36');
    svg.setAttribute('height', '18');
    const path = document.createElementNS(ns, 'path');
    path.setAttribute('d', this.canvas()?.thumbnail(36, 18) ?? '');
    Object.assign(path.style, { fill: 'none', stroke: tone, strokeWidth: '2', strokeLinecap: 'round' });
    svg.append(path);
    const text = document.createElement('span');
    text.textContent = label;
    chip.append(svg, text);
    document.body.append(chip);

    const start = from.getBoundingClientRect();
    const end = target.getBoundingClientRect();
    const size = chip.getBoundingClientRect();
    const x0 = start.left + start.width / 2 - size.width / 2;
    const y0 = start.top + start.height / 2 - size.height / 2;
    const x1 = end.left + end.width / 2 - size.width / 2;
    const y1 = end.top + end.height / 2 - size.height / 2;
    // Up and over: the midpoint lifts above both ends, so the chip arcs rather than slides.
    const lift = Math.min(y0, y1) - 80;
    chip.animate([
      { transform: `translate(${x0}px, ${y0}px) scale(1)`, opacity: 0 },
      { transform: `translate(${x0}px, ${y0 - 12}px) scale(1.05)`, opacity: 1, offset: 0.12 },
      { transform: `translate(${(x0 + x1) / 2}px, ${lift}px) scale(0.85)`, opacity: 1, offset: 0.55 },
      { transform: `translate(${x1}px, ${y1}px) scale(0.3)`, opacity: 0.2 }
    ], { duration: 820, easing: 'cubic-bezier(0.45, 0, 0.25, 1)', fill: 'forwards' })
      .finished.then(() => chip.remove(), () => chip.remove());
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
