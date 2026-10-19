import { Component, DestroyRef, OnInit, computed, effect, inject, signal, untracked } from '@angular/core';
import { CommonModule } from '@angular/common';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { OrderService } from '../../services/order';
import { formatMoney, formatSignedPercent } from '../../shared/format';
import { AccountHolds, holdsByAccount } from '../../shared/order-types';
import { ToolsContext } from '../tools-context';
import { CashBarComponent } from './cash-bar';
import { SellPlan, SellStrategy, SellableHolding, planSells } from './sell-plan';

export const STRATEGIES: readonly { id: SellStrategy; label: string; hint: string }[] = [
  { id: 'even', label: 'Even', hint: 'Trim every holding by the same percentage' },
  { id: 'winners', label: 'Winners first', hint: 'Sell the biggest gains first' },
  { id: 'losers', label: 'Losers first', hint: 'Sell the biggest losses first' }
];

export const QUICK_AMOUNTS: readonly number[] = [100, 500, 1000];

/** One holding beside the bar: what's left of it once the planned sells go through. */
export interface HoldingBlock {
  symbol: string;
  name: string;
  quantity: number;
  value: number | null;
  /** Unrealised gain or loss in percent, against average cost. */
  gainPercent: number | null;
  selling: number;
  /** Fraction (0-1) of the holding left after selling. */
  left: number;
  locked: boolean;
}

/** "AAPL", "AAPL and MSFT", "AAPL, MSFT and TSLA". */
function listOf(items: string[]): string {
  return items.length < 2 ? items.join('') : `${items.slice(0, -1).join(', ')} and ${items[items.length - 1]}`;
}

/** "$2,000", "2000.50", "2,000" as dollars, or null when it isn't a number. */
export function parseAmount(text: string): number | null {
  const cleaned = text.replace(/[$,\s]/g, '');
  if (!cleaned) {
    return null;
  }
  const amount = Number(cleaned);
  return Number.isFinite(amount) ? amount : null;
}

/**
 * Raise Cash's workspace: the client sets the cash they need, by dragging the bar, typing or the
 * quick amounts, and the sells that raise it are worked out live. Cash starts from buying power
 * (cash less what open buy orders hold), only shares not already on open sell orders are sold, and
 * sells are priced at the bid. Holdings can be locked so they're never sold.
 */
@Component({
  selector: 'app-raise-cash-tool',
  imports: [CommonModule, CashBarComponent],
  template: `
    <p *ngIf="state() === 'loading'" class="status" data-testid="raise-cash-loading">Loading your account…</p>

    <div *ngIf="state() === 'error'" class="status" role="alert">
      {{ context.error() }}
      <button type="button" class="link" (click)="context.load()">Try again</button>
    </div>

    <div *ngIf="state() === 'ready' && plan() as p" class="workspace" [class.empty]="!hasHoldings()">
      <div class="controls">
        <label class="amount">
          <span>Cash you need</span>
          <span class="field">
            <span aria-hidden="true">$</span>
            <input #amount type="text" inputmode="decimal" data-testid="target-input" [disabled]="!hasHoldings()"
                   [value]="p.target.toFixed(2)" (change)="typed(amount)" (keydown.enter)="typed(amount)" />
          </span>
        </label>

        <div class="chips" role="group" aria-label="Add to the amount">
          <button *ngFor="let amount of quickAmounts" type="button" class="chip" [disabled]="!hasHoldings()"
                  [attr.data-testid]="'chip-' + amount" (click)="add(amount)">+{{ whole(amount) }}</button>
        </div>

        <fieldset class="strategies" [disabled]="!hasHoldings()">
          <legend>Sell</legend>
          <label *ngFor="let s of strategies" class="strategy" [class.selected]="strategy() === s.id" [title]="s.hint">
            <input type="radio" name="raise-cash-strategy" [value]="s.id" [checked]="strategy() === s.id"
                   [attr.data-testid]="'strategy-' + s.id" (change)="strategy.set(s.id)" />
            {{ s.label }}
          </label>
        </fieldset>

        <p class="note" *ngIf="holdsError()" role="alert">
          Couldn’t check your open orders, so cash and shares they hold aren’t counted here.
        </p>
      </div>

      <app-cash-bar [min]="buyingPower()" [limit]="p.maxCash" [scaleMax]="p.fullCash" [value]="p.target"
                    [disabled]="!hasHoldings()" (valueChange)="requested.set($event)"></app-cash-bar>

      <div class="side">
        <p *ngIf="!hasHoldings()" class="empty-note" data-testid="no-holdings">
          This account has no holdings to sell, so there’s nothing to raise cash from.
          You have {{ money(buyingPower()) }} available.
        </p>

        <ul *ngIf="hasHoldings()" class="blocks" aria-label="Holdings">
          <li *ngFor="let b of blocks(); trackBy: bySymbol" class="block" [class.locked]="b.locked"
              [class.selling]="b.selling > 0" [attr.data-symbol]="b.symbol">
            <div class="meter" aria-hidden="true"><span [style.width.%]="b.left * 100"></span></div>
            <div class="head">
              <span class="symbol">{{ b.symbol }}</span>
              <span class="gain" [class.up]="(b.gainPercent ?? 0) > 0" [class.down]="(b.gainPercent ?? 0) < 0">
                {{ percent(b.gainPercent) }}
              </span>
              <button type="button" class="lock" [attr.aria-pressed]="b.locked" [attr.data-testid]="'lock-' + b.symbol"
                      [attr.aria-label]="'Don’t sell ' + b.symbol" (click)="toggleLock(b.symbol)">
                <svg viewBox="0 0 16 16" width="14" height="14" aria-hidden="true">
                  <rect x="3.5" y="7" width="9" height="6.5" rx="1.5" />
                  <path [attr.d]="b.locked ? 'M5.5 7V5a2.5 2.5 0 0 1 5 0v2' : 'M5.5 7V5a2.5 2.5 0 0 1 4.9-.7'" />
                </svg>
                {{ b.locked ? 'Locked' : 'Don’t sell' }}
              </button>
            </div>
            <div class="detail" [attr.data-testid]="'block-' + b.symbol">
              <ng-container *ngIf="b.selling > 0; else keep">Sell {{ b.selling }} of {{ b.quantity }}</ng-container>
              <ng-template #keep>{{ b.quantity }} {{ b.quantity === 1 ? 'share' : 'shares' }}</ng-template>
              · {{ money(b.value) }}
            </div>
          </li>
        </ul>

        <div *ngIf="hasHoldings()" class="summary" role="status" aria-live="polite" data-testid="raise-summary">
          <ng-container *ngIf="p.sells.length; else nothing">
            <strong>Target {{ money(p.target) }} → {{ money(p.cashAfter) }} in cash</strong>
            <span>Selling raises {{ money(p.raised) }}<ng-container *ngIf="p.cashAfter > p.target">, a little over, as only whole shares can be sold</ng-container>.</span>
          </ng-container>
          <ng-template #nothing>
            <span>Drag the bar up or type an amount to see what to sell. You have {{ money(buyingPower()) }} available.</span>
          </ng-template>
        </div>

        <p *ngIf="limitMessage() as message" class="limit" role="alert" data-testid="limit-message">{{ message }}</p>
        <p *ngIf="p.unpriced.length" class="note" data-testid="unpriced">
          {{ listOf(p.unpriced) }} {{ p.unpriced.length === 1 ? 'has' : 'have' }} no price yet, so {{ p.unpriced.length === 1 ? 'it’s' : 'they’re' }} left out.
        </p>
      </div>
    </div>
  `,
  styles: [`
    :host { display: block; }
    .status { padding: 40px 0; text-align: center; font-size: 14px; color: var(--muted-foreground); }
    .link { margin-left: 8px; background: none; border: 0; color: var(--primary); font-weight: 600; text-decoration: underline; cursor: pointer; }
    .workspace { display: grid; grid-template-columns: minmax(200px, 260px) auto minmax(0, 1fr); gap: 24px 40px; align-items: start; }
    .controls { display: flex; flex-direction: column; gap: 18px; }
    .amount { display: flex; flex-direction: column; gap: 6px; font-size: 13px; font-weight: 600; color: var(--muted-foreground); }
    .field {
      display: flex;
      align-items: center;
      gap: 4px;
      padding: 8px 12px;
      border-radius: var(--radius-md);
      border: 1px solid var(--border);
      background: var(--card);
      font-size: 18px;
      color: var(--foreground);
    }
    .field:focus-within { outline: 2px solid var(--ring); outline-offset: 1px; }
    .field input { flex: 1; min-width: 0; border: 0; background: transparent; color: inherit; font: inherit; font-variant-numeric: tabular-nums; outline: none; }
    .chips { display: flex; flex-wrap: wrap; gap: 8px; }
    .chip, .strategy, .lock {
      border-radius: 999px;
      border: 1px solid var(--border);
      background: transparent;
      color: var(--foreground);
      font-size: 13px;
      font-weight: 600;
      cursor: pointer;
    }
    .chip { padding: 6px 12px; }
    .chip:hover:not(:disabled), .lock:hover { background: var(--surface-hover); }
    .chip:disabled { opacity: 0.5; cursor: not-allowed; }
    .strategies { display: flex; flex-wrap: wrap; gap: 8px; border: 0; padding: 0; margin: 0; }
    .strategies legend { width: 100%; margin-bottom: 6px; font-size: 13px; font-weight: 600; color: var(--muted-foreground); }
    .strategies:disabled { opacity: 0.5; }
    .strategy { display: inline-flex; align-items: center; padding: 6px 12px; }
    .strategy input { position: absolute; opacity: 0; pointer-events: none; }
    .strategy.selected { border-color: var(--primary); background: color-mix(in srgb, var(--primary) 18%, transparent); }
    .strategy:focus-within { outline: 2px solid var(--ring); outline-offset: 2px; }
    app-cash-bar { justify-self: center; margin-right: 80px; }
    .side { display: flex; flex-direction: column; gap: 14px; min-width: 0; }
    .blocks { display: flex; flex-direction: column; gap: 8px; margin: 0; padding: 0; list-style: none; }
    .block { position: relative; padding: 10px 12px; border-radius: var(--radius-md); border: 1px solid var(--border); background: var(--card); overflow: hidden; }
    .meter { position: absolute; inset: 0; pointer-events: none; }
    .meter span {
      display: block;
      height: 100%;
      background: color-mix(in srgb, var(--primary) 12%, transparent);
      transition: width 0.15s ease;
    }
    .block.locked .meter span { background: color-mix(in srgb, var(--foreground) 8%, transparent); }
    .head, .detail { position: relative; }
    .head { display: flex; flex-wrap: wrap; align-items: center; gap: 6px 10px; }
    .symbol { font-weight: 600; color: var(--foreground); }
    .gain { font-size: 12px; color: var(--muted-foreground); font-variant-numeric: tabular-nums; }
    .gain.up { color: var(--gain); }
    .gain.down { color: var(--loss); }
    .lock { display: inline-flex; align-items: center; gap: 4px; margin-left: auto; padding: 3px 10px; font-size: 12px; white-space: nowrap; }
    .lock svg { fill: none; stroke: currentColor; stroke-width: 1.5; stroke-linecap: round; }
    .lock[aria-pressed='true'] { border-color: var(--warning); color: var(--warning); }
    .detail { margin-top: 4px; font-size: 12px; color: var(--muted-foreground); font-variant-numeric: tabular-nums; }
    .block.selling .detail { color: var(--foreground); }
    .summary { display: flex; flex-direction: column; gap: 4px; font-size: 14px; color: var(--muted-foreground); }
    .summary strong { font-size: 16px; color: var(--foreground); font-variant-numeric: tabular-nums; }
    .limit { padding: 10px 12px; border-radius: var(--radius-md); background: var(--warning-muted); color: var(--warning); font-size: 13px; }
    .note, .empty-note { font-size: 13px; color: var(--muted-foreground); }
    .empty-note { padding: 14px; border-radius: var(--radius-md); border: 1px dashed var(--border); }
    @media (prefers-reduced-motion: reduce) { .meter span { transition: none; } }
    @media (max-width: 900px) {
      .workspace { grid-template-columns: auto minmax(0, 1fr); }
      .controls { grid-column: 1 / -1; }
      app-cash-bar { margin-right: 72px; }
    }
  `]
})
export class RaiseCashToolComponent implements OnInit {
  readonly context = inject(ToolsContext);
  private readonly orderService = inject(OrderService);
  private readonly destroyRef = inject(DestroyRef);

  readonly strategies = STRATEGIES;
  readonly quickAmounts = QUICK_AMOUNTS;

  readonly strategy = signal<SellStrategy>('even');
  readonly locked = signal<ReadonlySet<string>>(new Set());
  /** The cash asked for, before it's kept within reach; null until the client sets one. */
  readonly requested = signal<number | null>(null);

  /** What the account's open orders hold back; null until they've loaded. */
  private readonly holds = signal<Record<string, AccountHolds> | null>(null);
  readonly holdsError = signal(false);

  readonly state = computed<'loading' | 'error' | 'ready'>(() => {
    if (this.context.error()) {
      return 'error';
    }
    return this.context.loading() || !this.context.account() || this.holds() === null ? 'loading' : 'ready';
  });

  private readonly accountId = computed(() => this.context.account()?.accountId ?? null);
  private readonly accountHolds = computed(() => this.holds()?.[this.accountId() ?? ''] ?? null);

  /** Cash less what open buy orders hold: where the bar starts. */
  readonly buyingPower = computed(() => Math.max(0, (this.context.account()?.cash ?? 0) - (this.accountHolds()?.cash ?? 0)));

  readonly hasHoldings = computed(() => this.context.holdings().length > 0);

  /** Each holding with the shares open sell orders already hold, priced at the bid where it's known. */
  private readonly sellable = computed<SellableHolding[]>(() => {
    const onHold = this.accountHolds()?.shares ?? {};
    const offsets = this.context.quoteOffsets();
    return this.context.holdings().map(h => ({
      symbol: h.symbol,
      quantity: h.quantity,
      onHold: onHold[h.symbol] ?? 0,
      price: h.price === null ? null : h.price + (offsets[h.symbol]?.bid ?? 0),
      averageCost: h.averageCost
    }));
  });

  /** The sells for the amount asked, recomputed straight away on every change and price tick. */
  readonly plan = computed<SellPlan>(() => planSells({
    holdings: this.sellable(),
    cash: this.buyingPower(),
    target: this.requested() ?? this.buyingPower(),
    strategy: this.strategy(),
    locked: this.locked()
  }));

  readonly blocks = computed<HoldingBlock[]>(() => {
    const selling = new Map(this.plan().sells.map(s => [s.symbol, s.quantity]));
    const locked = this.locked();
    return this.context.holdings().map(h => {
      const sold = selling.get(h.symbol) ?? 0;
      return {
        symbol: h.symbol,
        name: h.name,
        quantity: h.quantity,
        value: h.marketValue,
        gainPercent: h.totalReturnPercent,
        selling: sold,
        left: h.quantity ? (h.quantity - sold) / h.quantity : 0,
        locked: locked.has(h.symbol)
      };
    });
  });

  /** Why the handle stopped short of the amount asked, or null when it didn't. */
  readonly limitMessage = computed<string | null>(() => {
    const plan = this.plan();
    if (!plan.capped || !this.hasHoldings()) {
      return null;
    }
    const reasons: string[] = [];
    if (plan.locked.length) {
      reasons.push(`${listOf(plan.locked)} ${plan.locked.length === 1 ? 'is' : 'are'} locked`);
    }
    if (Object.values(this.accountHolds()?.shares ?? {}).some(shares => shares > 0)) {
      reasons.push('some shares are already on open sell orders');
    }
    if (plan.unpriced.length) {
      reasons.push(`${listOf(plan.unpriced)} ${plan.unpriced.length === 1 ? 'has' : 'have'} no price yet`);
    }
    const why = reasons.length ? `${listOf(reasons)}.` : 'that sells everything you hold.';
    return `You can reach at most ${formatMoney(plan.maxCash)} in cash: ${why}`;
  });

  money = formatMoney;
  listOf = listOf;

  constructor() {
    // Sells fill at the bid, so ask for every holding's quote.
    effect(() => {
      const symbols = this.context.holdings().map(h => h.symbol);
      untracked(() => this.context.requestQuotes(symbols));
    });
    // Another account starts over: its own amount, nothing locked. Keyed on the id, since the
    // account itself is revalued on every price tick.
    effect(() => {
      this.accountId();
      untracked(() => {
        this.requested.set(null);
        this.locked.set(new Set());
      });
    });
  }

  ngOnInit(): void {
    this.orderService.getOpenOrders()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: orders => this.holds.set(holdsByAccount(orders)),
        // Without them, assume nothing is held; the server still refuses a sell of shares already promised.
        error: () => {
          this.holds.set({});
          this.holdsError.set(true);
        }
      });
  }

  /** A typed amount: the handle moves to it, and the box shows what was actually planned. */
  typed(input: HTMLInputElement): void {
    const amount = parseAmount(input.value);
    if (amount !== null) {
      this.requested.set(amount);
    }
    input.value = this.plan().target.toFixed(2);
  }

  add(amount: number): void {
    this.requested.set(this.plan().target + amount);
  }

  toggleLock(symbol: string): void {
    this.locked.update(current => {
      const next = new Set(current);
      if (!next.delete(symbol)) {
        next.add(symbol);
      }
      return next;
    });
  }

  whole(amount: number): string {
    return formatMoney(amount).replace(/\.00$/, '');
  }

  percent(value: number | null): string {
    return formatSignedPercent(value);
  }

  bySymbol(_index: number, block: HoldingBlock): string {
    return block.symbol;
  }
}
