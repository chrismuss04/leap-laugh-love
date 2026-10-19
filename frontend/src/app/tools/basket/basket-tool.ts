import { Component, DestroyRef, ElementRef, computed, effect, inject, signal, untracked, viewChild } from '@angular/core';
import { CommonModule } from '@angular/common';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { SymbolSearchComponent } from '../../dashboard/symbol-search/symbol-search';
import { liveInstruments, marketMovers } from '../../instruments/market-movers';
import { InstrumentOverviewRow, InstrumentsService } from '../../services/instruments';
import { direction, formatMoney, formatSignedPercent } from '../../shared/format';
import { isTradable } from '../../shared/tradable';
import { ToolsContext } from '../tools-context';
import { BasketItem, BasketService, MAX_BASKET, MIN_BASKET } from './basket';
import { BasketRowComponent } from './basket-row';

/** What a mover row carries while it's dragged, so the basket only takes drags from its own movers. */
export const BASKET_DRAG_TYPE = 'application/x-leap-basket-symbol';

/**
 * Basket Builder's workspace: the stocks the client is collecting to buy together, each at its
 * live price with today's change. Stocks come from the symbol search, or from today's market
 * movers - dragged into the basket, or added with their Add button for anyone not using a mouse.
 */
@Component({
  selector: 'app-basket-tool',
  imports: [CommonModule, SymbolSearchComponent, BasketRowComponent],
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
            <li *ngFor="let item of basket.items(); trackBy: trackBySymbol" [attr.data-symbol]="item.symbol">
              <app-basket-row [item]="item" [price]="priceOf(item.symbol)" [changePercent]="changeOf(item.symbol)"
                              (remove)="remove(item)"></app-basket-row>
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
    .footer { display: flex; justify-content: flex-end; }
    .link { background: none; padding: 0; font-size: 13px; font-weight: 600; color: var(--primary); }
    .link.danger { color: var(--loss); }
    .confirm { display: flex; flex-wrap: wrap; align-items: center; gap: 8px 12px; font-size: 13px; color: var(--foreground); }
    .confirm button { padding: 6px 14px; border-radius: 999px; background: var(--muted); color: var(--foreground); font-size: 13px; font-weight: 600; }
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
