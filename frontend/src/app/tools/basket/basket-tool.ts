import { Component, DestroyRef, ElementRef, computed, effect, inject, signal, untracked, viewChild } from '@angular/core';
import { CommonModule } from '@angular/common';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { SymbolSearchComponent } from '../../dashboard/symbol-search/symbol-search';
import { liveInstruments, marketMovers } from '../../instruments/market-movers';
import { InstrumentOverviewRow, InstrumentsService } from '../../services/instruments';
import { direction, formatMoney, formatSignedPercent } from '../../shared/format';
import { isTradable } from '../../shared/tradable';
import { projectOrders } from '../../shared/order-plan';
import { ToolsContext } from '../tools-context';
import { BasketItem, BasketService, MAX_BASKET, MIN_BASKET } from './basket';
import { BasketRowComponent } from './basket-row';
import { dividerStep, dragDivider, moveDivider, parseCents, setAmount } from './basket-split';
import { BasketRounding, basketMaxCents, roundShares, useLeftovers } from './basket-shares';
import { SplitBarComponent } from './split-bar';

/** What the leftovers prompt offers before the basket goes into the plan. */
interface LeftoverOffer {
  leftoverCents: number;
  extras: { symbol: string; shares: number }[];
  extraCostCents: number;
}

/** What a mover row carries while it's dragged, so the basket only takes drags from its own movers. */
export const BASKET_DRAG_TYPE = 'application/x-leap-basket-symbol';

/**
 * Basket Builder's workspace: the stocks the client is collecting to buy together, each at its
 * live price with today's change. Stocks come from the symbol search, or from today's market
 * movers - dragged into the basket, or added with their Add button for anyone not using a mouse.
 */
@Component({
  selector: 'app-basket-tool',
  imports: [CommonModule, SymbolSearchComponent, BasketRowComponent, SplitBarComponent],
  template: `
    <div class="workspace">
      <section class="panel basket" aria-labelledby="basket-title">
        <div class="head">
          <h2 id="basket-title">Your basket</h2>
          <span class="count num" data-testid="basket-count">{{ basket.count() }} / {{ max }}</span>
        </div>

        <app-symbol-search [livePrices]="context.prices()" [unavailable]="unavailable" [shortcut]="false"
                           placeholder="Search stocks to add" (picked)="add($event.symbol, $event.name)"></app-symbol-search>
        <p *ngIf="notice() as message" class="notice" role="alert" data-testid="basket-notice">{{ message }}</p>

        <div class="drop" data-testid="basket-drop" [class.over]="dragOver()"
             (dragover)="onDragOver($event)" (dragleave)="dragOver.set(false)" (drop)="onDrop($event)">
          <ul *ngIf="!basket.isEmpty(); else empty" class="rows" role="list">
            <li *ngFor="let item of basket.items(); let i = index; trackBy: trackBySymbol" [attr.data-symbol]="item.symbol"
                [class.not-enough]="rounding().buys[i]?.notEnough">
              <app-basket-row [item]="item" [price]="priceOf(item.symbol)" [changePercent]="changeOf(item.symbol)"
                              (remove)="remove(item)">
                <div *ngIf="splitting() && rounding().buys[i] as buy" class="split-cells">
                  <label class="row-amount">
                    <span class="sr-only">Amount for {{ item.symbol }}</span>
                    <span aria-hidden="true">$</span>
                    <input type="text" inputmode="decimal" data-testid="row-amount" [value]="dollars(buy.amountCents)"
                           [disabled]="!total()" (change)="onRowAmount(i, $any($event.target))" />
                  </label>
                  <span class="shares num" data-testid="row-shares">{{ sharesText(buy) }}</span>
                  <span *ngIf="buy.notEnough" class="warn" data-testid="row-not-enough">Not enough for 1 share</span>
                </div>
              </app-basket-row>
            </li>
          </ul>
          <ng-template #empty>
            <p class="empty" data-testid="basket-empty">
              Search for a stock, or drag one here from Market movers. A basket holds {{ min }} to {{ max }} stocks.
            </p>
          </ng-template>
        </div>

        <p *ngIf="!basket.isEmpty() && !basket.ready()" class="hint" data-testid="basket-min">
          Add at least {{ min }} stocks to buy them as a basket.
        </p>

        <section *ngIf="splitting()" class="split" aria-labelledby="split-title" data-testid="basket-split">
          <h3 id="split-title">Amount to invest</h3>
          <div class="amount">
            <label class="amount-field">
              <span class="sr-only">Amount to invest, in dollars</span>
              <span aria-hidden="true">$</span>
              <input type="text" inputmode="decimal" placeholder="0.00" data-testid="basket-amount"
                     [value]="total() === null ? '' : dollars(total()!)" [disabled]="maxCents() === null"
                     (change)="onAmount($any($event.target))" />
            </label>
            <input type="range" class="slider" data-testid="basket-slider" aria-label="Amount to invest"
                   min="0" [max]="maxCents() ?? 0" step="100" [value]="total() ?? 0" [disabled]="!maxCents()"
                   [attr.aria-valuetext]="money((total() ?? 0) / 100)" (input)="onSlide($any($event.target).value)" />
            <span class="max num" data-testid="basket-max">Up to {{ money((maxCents() ?? 0) / 100) }}</span>
          </div>
          <p *ngIf="amountNote() as note" class="amount-note" role="status" data-testid="amount-note">{{ note }}</p>

          <app-split-bar [segments]="segments()" [disabled]="!total()"
                         (dragged)="onDivider($event.divider, $event.positionCents)"
                         (stepped)="onDividerStep($event.divider, $event.steps)"></app-split-bar>
          <p *ngIf="!total()" class="hint">Enter an amount, then drag the dividers to split it. It starts even.</p>

          <div *ngIf="total()" class="totals" data-testid="basket-totals">
            <span>Invested <b class="num">{{ money(rounding().investedCents / 100) }}</b></span>
            <span>Cash left over <b class="num">{{ money(rounding().leftoverCents / 100) }}</b></span>
          </div>
          <p *ngIf="total() && leftOut().length" class="note" data-testid="left-out">
            Left out of the plan, not enough for 1 share: {{ leftOut().join(', ') }}.
          </p>

          <div *ngIf="offer() as o; else addButton" class="confirm offer" role="alertdialog" aria-labelledby="offer-text"
               data-testid="leftover-prompt" (keydown.escape)="offer.set(null)">
            <span id="offer-text">
              You have <b>{{ money(o.leftoverCents / 100) }}</b> left over. It could buy
              <b>{{ extrasText(o) }}</b> ({{ money(o.extraCostCents / 100) }}), leaving
              <b>{{ money((o.leftoverCents - o.extraCostCents) / 100) }}</b>.
            </span>
            <button #useLeftoversButton type="button" class="primary" data-testid="use-leftovers" (click)="send(true)">Use leftovers and add</button>
            <button type="button" data-testid="add-without" (click)="send(false)">Add without</button>
            <button type="button" data-testid="offer-cancel" (click)="offer.set(null)">Cancel</button>
          </div>
          <ng-template #addButton>
            <button type="button" class="primary add-plan" data-testid="basket-add" [disabled]="!canAdd()" (click)="addToPlan()">
              Add to plan
            </button>
          </ng-template>
          <p *ngIf="addedNote() as note" class="added" role="status" data-testid="added-note">{{ note }}</p>
        </section>

        <div *ngIf="!basket.isEmpty()" class="footer">
          <button #clearButton *ngIf="!confirmingClear(); else confirm" type="button" class="link danger" data-testid="basket-clear"
                  (click)="askClear()">Clear basket</button>
          <ng-template #confirm>
            <div class="confirm" role="alertdialog" aria-labelledby="clear-question" data-testid="clear-confirm"
                 (keydown.escape)="cancelClear()">
              <span id="clear-question">Remove all {{ basket.count() }} stocks from your basket?</span>
              <button type="button" class="danger" data-testid="clear-yes" (click)="confirmClear()">Clear basket</button>
              <button #cancelButton type="button" data-testid="clear-no" (click)="cancelClear()">Cancel</button>
            </div>
          </ng-template>
        </div>
      </section>

      <section class="panel movers" aria-labelledby="movers-title">
        <h2 id="movers-title">Market movers</h2>
        <p class="sub">Today's biggest moves among stocks you can trade. Drag one into your basket, or use Add.</p>
        <p *ngIf="moversLoading()" class="pending">Loading market movers…</p>
        <p *ngIf="moversError()" class="pending" data-testid="movers-error">
          Market movers are unavailable. <button type="button" class="link" (click)="loadMovers()">Try again</button>
        </p>
        <div *ngFor="let group of moverGroups()" class="group" [attr.data-group]="group.key">
          <h3>{{ group.label }}</h3>
          <ol class="mover-list">
            <li *ngFor="let row of group.rows; trackBy: trackBySymbol" class="mover" [attr.data-symbol]="row.symbol"
                [attr.draggable]="!basket.reason(row.symbol)" (dragstart)="onDragStart($event, row)"
                (dragend)="dragOver.set(false)">
              <span class="stock">
                <span class="symbol">{{ row.symbol }}</span>
                <span class="name">{{ row.name }}</span>
              </span>
              <span class="num m-price">{{ money(row.price) }}</span>
              <span class="num m-change" [class.text-gain]="trend(row.changePercent) === 'gain'"
                    [class.text-loss]="trend(row.changePercent) === 'loss'">{{ percent(row.changePercent) }}</span>
              <button type="button" class="add" [disabled]="!!basket.reason(row.symbol)" (click)="add(row.symbol, row.name)"
                      [attr.aria-label]="basket.reason(row.symbol) ? row.symbol + ': ' + basket.reason(row.symbol) : 'Add ' + row.symbol + ' to basket'">
                <span class="label-long">{{ basket.reason(row.symbol) ?? 'Add' }}</span>
                <span class="label-short" aria-hidden="true">{{ shortLabel(basket.reason(row.symbol)) }}</span>
              </button>
            </li>
          </ol>
          <p *ngIf="!group.rows.length" class="pending">No {{ group.label.toLowerCase() }} today.</p>
        </div>
      </section>
    </div>

    <div class="sr-only" aria-live="polite" role="status" data-testid="basket-announcer">{{ basket.announcement() }}</div>
  `,
  styles: [`
    :host { display: block; }
    .workspace { display: grid; grid-template-columns: minmax(0, 3fr) minmax(0, 2fr); gap: 20px; align-items: start; }
    .panel { display: flex; flex-direction: column; gap: 12px; padding: 16px; border-radius: var(--radius-lg); border: 1px solid var(--border); background: var(--card); }
    .head { display: flex; align-items: baseline; justify-content: space-between; }
    h2 { font-size: 16px; font-weight: 600; color: var(--foreground); }
    h3 { margin: 4px 0 6px; font-size: 13px; font-weight: 600; color: var(--muted-foreground); }
    .count { font-size: 13px; color: var(--muted-foreground); }
    .sub, .pending, .hint, .empty { font-size: 13px; color: var(--muted-foreground); }
    .notice { font-size: 13px; color: var(--loss); }
    .drop { min-height: 120px; padding: 8px; border-radius: var(--radius-md); border: 1px dashed var(--border); transition: border-color 0.15s ease, background 0.15s ease; }
    .drop.over { border-color: var(--primary); background: color-mix(in srgb, var(--primary) 8%, transparent); }
    .empty { padding: 32px 12px; text-align: center; }
    .rows, .mover-list { margin: 0; padding: 0; list-style: none; }
    .rows { display: flex; flex-direction: column; gap: 8px; }
    .split-cells { grid-column: 1 / -1; display: flex; flex-wrap: wrap; align-items: center; gap: 6px 14px; font-size: 13px; color: var(--muted-foreground); }
    .row-amount, .amount-field { display: inline-flex; align-items: center; gap: 4px; padding: 4px 10px; border-radius: 8px; border: 1px solid var(--border); background: var(--card); color: var(--foreground); }
    .row-amount input, .amount-field input { width: 90px; background: transparent; border: 0; color: var(--foreground); font: inherit; font-variant-numeric: tabular-nums; }
    .amount-field input { width: 120px; font-size: 15px; }
    .rows li.not-enough app-basket-row { border-color: color-mix(in srgb, var(--warning, #f5b041) 60%, var(--border)); }
    .warn { color: var(--warning, #f5b041); font-weight: 600; }
    .split { display: flex; flex-direction: column; gap: 10px; padding-top: 12px; border-top: 1px solid var(--border); }
    .amount { display: flex; flex-wrap: wrap; align-items: center; gap: 10px 14px; }
    .slider { flex: 1; min-width: 160px; accent-color: var(--primary); }
    .max { font-size: 13px; color: var(--muted-foreground); }
    .amount-note { font-size: 13px; color: var(--warning, #f5b041); }
    .totals { display: flex; flex-wrap: wrap; gap: 6px 20px; font-size: 13px; color: var(--muted-foreground); }
    .totals b { color: var(--foreground); }
    .note, .added { font-size: 13px; color: var(--muted-foreground); }
    .primary { align-self: flex-end; padding: 8px 18px; border-radius: 999px; background: var(--primary); color: var(--primary-foreground, #0b0b0e); font-size: 13px; font-weight: 600; }
    .primary:disabled { opacity: 0.45; cursor: not-allowed; }
    .offer { padding: 10px 12px; border-radius: var(--radius-md); background: var(--surface); }
    .offer .primary { align-self: auto; }
    .footer { display: flex; justify-content: flex-end; }
    .link { background: none; padding: 0; font-size: 13px; font-weight: 600; color: var(--primary); }
    .link.danger { color: var(--loss); }
    .confirm { display: flex; flex-wrap: wrap; align-items: center; gap: 8px 12px; font-size: 13px; color: var(--foreground); }
    .confirm button:not(.primary) { padding: 6px 14px; border-radius: 999px; background: var(--muted); color: var(--foreground); font-size: 13px; font-weight: 600; }
    .confirm button.danger { background: var(--loss-muted); color: var(--loss); }
    .mover-list { display: flex; flex-direction: column; gap: 4px; }
    .mover {
      display: grid;
      grid-template-columns: minmax(0, 1fr) auto 64px 136px;
      align-items: center;
      gap: 10px;
      padding: 8px 10px;
      border-radius: var(--radius-md);
      background: var(--surface);
    }
    .mover[draggable='true'] { cursor: grab; }
    .mover .num { text-align: right; font-size: 13px; }
    .stock { display: flex; flex-direction: column; min-width: 0; }
    .symbol { font-weight: 600; color: var(--foreground); }
    .name { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-size: 12px; color: var(--muted-foreground); }
    .add { padding: 5px 10px; border-radius: 999px; border: 1px solid color-mix(in srgb, var(--primary) 45%, var(--border)); background: color-mix(in srgb, var(--primary) 10%, transparent); color: var(--foreground); font-size: 12px; font-weight: 600; }
    .add { white-space: nowrap; }
    .label-short { display: none; }
    .add:disabled { border-color: var(--border); background: transparent; color: var(--muted-foreground); }
    @media (max-width: 900px) { .workspace { grid-template-columns: 1fr; } }
    /* Narrow: price over change, so the name and the button keep their room. */
    @media (max-width: 640px) {
      .mover { grid-template-columns: minmax(0, 1fr) auto auto; grid-template-areas: 'stock price add' 'stock change add'; row-gap: 2px; }
      .mover .stock { grid-area: stock; }
      .mover .m-price { grid-area: price; }
      .mover .m-change { grid-area: change; }
      /* One width for every state, so the price and change line up down the list. */
      .mover .add { grid-area: add; min-width: 76px; }
      .label-long { display: none; }
      .label-short { display: inline; }
    }
    @media (prefers-reduced-motion: reduce) { .drop { transition: none; } }
  `]
})
export class BasketToolComponent {
  readonly context = inject(ToolsContext);
  readonly basket = inject(BasketService);
  private readonly instruments = inject(InstrumentsService);
  private readonly destroyRef = inject(DestroyRef);

  readonly min = MIN_BASKET;
  readonly max = MAX_BASKET;

  /** Why the last add was refused, until the next one goes in. */
  readonly notice = signal<string | null>(null);
  readonly dragOver = signal(false);
  readonly confirmingClear = signal(false);
  private readonly search = viewChild(SymbolSearchComponent);
  private readonly cancelButton = viewChild<ElementRef<HTMLButtonElement>>('cancelButton');
  private readonly clearButton = viewChild<ElementRef<HTMLButtonElement>>('clearButton');
  private readonly useLeftoversButton = viewChild<ElementRef<HTMLButtonElement>>('useLeftoversButton');

  /** Today's snapshot of every instrument: the movers, and each stock's baseline for today's change. */
  private readonly overview = signal<InstrumentOverviewRow[]>([]);
  readonly moversLoading = signal(false);
  readonly moversError = signal(false);

  private readonly bySymbol = computed(() => new Map(this.overview().map(row => [row.symbol, row])));

  /** Today's gainers and losers among tradable stocks, repriced as the stream ticks. */
  readonly moverGroups = computed(() => {
    if (this.moversLoading() || this.moversError()) {
      return [];
    }
    const rows = liveInstruments(this.overview().filter(row => isTradable(row.symbol)), this.context.prices(), {});
    return [
      { key: 'gainers', label: 'Top gainers', rows: marketMovers(rows, 'gainers') },
      { key: 'losers', label: 'Top losers', rows: marketMovers(rows, 'losers') }
    ];
  });

  readonly unavailable = (symbol: string): string | null => this.basket.reason(symbol);

  readonly total = this.basket.totalCents;
  /** The split shows once the basket can be bought: two stocks or more. */
  readonly splitting = computed(() => this.basket.ready());
  readonly amountNote = signal<string | null>(null);
  readonly addedNote = signal<string | null>(null);
  /** The leftovers prompt, while it's up. */
  readonly offer = signal<LeftoverOffer | null>(null);

  readonly segments = computed(() =>
    this.basket.items().map((item, i) => ({ symbol: item.symbol, amountCents: this.basket.amounts()[i] ?? 0 })));

  /** What a market buy of each stock fills at: the ask, else the last trade until the quote arrives. */
  private readonly asks = computed(() => {
    const offsets = this.context.quoteOffsets();
    return Object.fromEntries(this.basket.symbols().map(symbol => {
      const last = this.priceOf(symbol);
      return [symbol, last === null ? null : last + (offsets[symbol]?.ask ?? 0)];
    }));
  });

  readonly rounding = computed<BasketRounding>(() => roundShares(this.basket.symbols(), this.basket.amounts(), this.asks()));
  readonly leftOut = computed(() => this.rounding().buys.filter(b => b.notEnough).map(b => b.symbol));

  /**
   * The most the basket can cost: the account's cash, with the plan's other orders (not earlier
   * Basket ones, which Add to plan replaces) priced as the tray prices them. Null until the
   * account loads.
   */
  readonly maxCents = computed<number | null>(() => {
    const account = this.context.account();
    if (!account) {
      return null;
    }
    const plan = this.context.plan;
    const others = plan.accountId() === account.accountId ? plan.sorted().filter(o => o.source !== 'basket') : [];
    const priced = others.length ? projectOrders({
      positions: this.context.holdings().map(h => ({ symbol: h.symbol, quantity: h.quantity })),
      prices: this.context.prices(), quoteOffsets: this.context.quoteOffsets(), cash: account.cash, orders: others
    }).orders : [];
    return basketMaxCents(account.cash, priced);
  });

  readonly canAdd = computed(() => {
    const total = this.total();
    const max = this.maxCents();
    return this.splitting() && !!total && max !== null && total <= max && this.rounding().buys.some(b => b.shares > 0);
  });

  readonly money = formatMoney;
  readonly percent = formatSignedPercent;
  readonly trend = direction;

  constructor() {
    this.loadMovers();
    // Basket stocks and movers mostly aren't held, so the tools page wouldn't stream their prices otherwise.
    effect(() => {
      const movers = this.moverGroups().flatMap(group => group.rows.map(row => row.symbol));
      const symbols = [...new Set([...this.basket.symbols(), ...movers])];
      untracked(() => this.context.toolSymbols.set(symbols));
    });
    this.destroyRef.onDestroy(() => this.context.toolSymbols.set([]));
    // Buys fill at the ask, so every basket stock is quoted.
    effect(() => {
      const symbols = this.basket.symbols();
      untracked(() => this.context.requestQuotes(symbols));
    });
    // Cash can fall (prices move, the plan grows) below an amount that fitted: bring it down to fit.
    effect(() => {
      const max = this.maxCents();
      const total = this.total();
      if (max !== null && total !== null && total > max) {
        untracked(() => {
          this.basket.setTotal(max);
          this.amountNote.set(`Your amount was lowered to ${this.money(max / 100)}, the most you can invest now.`);
        });
      }
    });
  }

  loadMovers(): void {
    this.moversLoading.set(true);
    this.moversError.set(false);
    this.instruments.getOverview('today')
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: overview => {
          this.overview.set(overview.instruments);
          this.moversLoading.set(false);
        },
        error: () => {
          this.moversLoading.set(false);
          this.moversError.set(true);
        }
      });
  }

  /** The live price, else today's snapshot; null until either has one. */
  priceOf(symbol: string): number | null {
    const live = this.context.prices()[symbol];
    return live > 0 ? live : this.bySymbol().get(symbol)?.price ?? null;
  }

  /** Today's change in percent at the live price, or null without a baseline. */
  changeOf(symbol: string): number | null {
    const baseline = this.bySymbol().get(symbol)?.baselinePrice ?? null;
    const price = this.priceOf(symbol);
    return baseline && baseline > 0 && price !== null ? ((price - baseline) / baseline) * 100 : null;
  }

  add(symbol: string, name: string | null): void {
    this.notice.set(this.basket.add(symbol, name ?? this.bySymbol().get(symbol)?.name ?? null));
  }

  remove(item: BasketItem): void {
    this.notice.set(null);
    this.basket.remove(item.symbol);
  }

  onDragStart(event: DragEvent, row: InstrumentOverviewRow): void {
    if (!event.dataTransfer || this.basket.reason(row.symbol)) {
      event.preventDefault();
      return;
    }
    event.dataTransfer.setData(BASKET_DRAG_TYPE, row.symbol);
    event.dataTransfer.setData('text/plain', row.symbol);
    event.dataTransfer.effectAllowed = 'copy';
  }

  /** Accepts only a mover being dragged; anything else dropped on the page is left alone. */
  onDragOver(event: DragEvent): void {
    if (!event.dataTransfer?.types.includes(BASKET_DRAG_TYPE)) {
      return;
    }
    event.preventDefault();
    event.dataTransfer.dropEffect = 'copy';
    this.dragOver.set(true);
  }

  onDrop(event: DragEvent): void {
    const symbol = event.dataTransfer?.getData(BASKET_DRAG_TYPE);
    this.dragOver.set(false);
    if (!symbol) {
      return;
    }
    event.preventDefault();
    this.add(symbol, null);
  }

  /** Asks before clearing, with focus on Cancel so a stray Enter doesn't empty the basket. */
  askClear(): void {
    this.confirmingClear.set(true);
    setTimeout(() => this.cancelButton()?.nativeElement.focus());
  }

  confirmClear(): void {
    this.basket.clear();
    this.confirmingClear.set(false);
    this.notice.set(null);
    setTimeout(() => this.search()?.focus());
  }

  cancelClear(): void {
    this.confirmingClear.set(false);
    setTimeout(() => this.clearButton()?.nativeElement.focus());
  }

  /** A typed amount: blank clears it, more than the maximum is brought down to it. */
  onAmount(input: HTMLInputElement): void {
    const max = this.maxCents() ?? 0;
    this.addedNote.set(null);
    if (input.value.trim() === '') {
      this.basket.setTotal(null);
      this.amountNote.set(null);
      return;
    }
    const cents = parseCents(input.value);
    if (cents === null) {
      this.amountNote.set('Enter an amount in dollars, like 2500 or 2500.50.');
      input.value = this.total() === null ? '' : this.dollars(this.total()!);
      return;
    }
    this.amountNote.set(cents > max ? `You can invest up to ${this.money(max / 100)}.` : null);
    this.basket.setTotal(Math.min(cents, max) || null);
    input.value = this.total() === null ? '' : this.dollars(this.total()!);
  }

  onSlide(value: string): void {
    this.amountNote.set(null);
    this.addedNote.set(null);
    this.basket.setTotal(Number(value) || null);
  }

  onDivider(divider: number, positionCents: number): void {
    this.addedNote.set(null);
    this.basket.setAmounts(dragDivider(this.basket.amounts(), divider, positionCents, dividerStep(this.total() ?? 0)));
  }

  onDividerStep(divider: number, steps: number): void {
    this.addedNote.set(null);
    this.basket.setAmounts(moveDivider(this.basket.amounts(), divider, steps * dividerStep(this.total() ?? 0)));
  }

  /** A typed amount for one stock: the others make up the difference, so the total stays put. */
  onRowAmount(index: number, input: HTMLInputElement): void {
    const cents = parseCents(input.value);
    this.addedNote.set(null);
    if (cents !== null) {
      this.basket.setAmounts(setAmount(this.basket.amounts(), index, cents));
    }
    input.value = this.dollars(this.basket.amounts()[index]);
  }

  /** Straight into the plan, unless the leftover could buy more shares: then the client chooses. */
  addToPlan(): void {
    if (!this.canAdd()) {
      return;
    }
    const current = this.rounding();
    const { rounding, extras } = useLeftovers(current, this.total()!);
    const extraList = Object.entries(extras).map(([symbol, shares]) => ({ symbol, shares }));
    if (!extraList.length) {
      this.send(false);
      return;
    }
    this.offer.set({
      leftoverCents: current.leftoverCents,
      extras: extraList,
      extraCostCents: rounding.investedCents - current.investedCents
    });
    setTimeout(() => this.useLeftoversButton()?.nativeElement.focus());
  }

  /**
   * One buy per stock with shares, tagged Basket, replacing earlier Basket buys. Worked out again at
   * the prices now, in case they moved while the prompt was up.
   */
  send(withLeftovers: boolean): void {
    const account = this.context.account();
    const total = this.total();
    this.offer.set(null);
    if (!account || !total) {
      return;
    }
    const rounding = withLeftovers ? useLeftovers(this.rounding(), total).rounding : this.rounding();
    const buys = rounding.buys.filter(b => b.shares > 0);
    this.context.plan.addPlan('basket', account.accountId,
      buys.map(b => ({ symbol: b.symbol, side: 'BUY' as const, quantity: b.shares })));
    const leftOut = rounding.buys.filter(b => b.shares === 0).map(b => b.symbol);
    // What went in, which with leftovers isn't what the totals above show.
    this.addedNote.set(`${this.plural(buys.length, 'buy')} added to your plan`
      + `${withLeftovers ? ', using leftovers' : ''}: ${this.money(rounding.investedCents / 100)} invested,`
      + ` ${this.money(rounding.leftoverCents / 100)} left over.`
      + (leftOut.length ? ` Left out, not enough for 1 share: ${leftOut.join(', ')}.` : ''));
  }

  extrasText(offer: LeftoverOffer): string {
    return offer.extras.map(e => `+${e.shares} ${e.symbol}`).join(', ');
  }

  sharesText(buy: BasketRounding['buys'][number]): string {
    return buy.price === null
      ? 'Waiting for a price'
      : `${this.plural(buy.shares, 'share')} at ${this.money(buy.price)} · ${this.money(buy.leftoverCents / 100)} left`;
  }

  /** Cents as "1234.50", for an input. */
  dollars(cents: number): string {
    return (cents / 100).toFixed(2);
  }

  plural(count: number, noun: string): string {
    return `${count} ${noun}${count === 1 ? '' : 's'}`;
  }

  /** The mover button's label on narrow screens, where the full reason doesn't fit; the aria-label keeps it whole. */
  shortLabel(reason: string | null): string {
    if (reason === null) {
      return 'Add';
    }
    return reason === 'Basket full' ? 'Full' : '✓ Added';
  }

  trackBySymbol(_index: number, row: { symbol: string }): string {
    return row.symbol;
  }
}
