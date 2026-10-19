import { Component, DestroyRef, ElementRef, HostListener, computed, effect, inject, signal, untracked, viewChild } from '@angular/core';
import { CommonModule } from '@angular/common';
import { SymbolSearchComponent } from '../../dashboard/symbol-search/symbol-search';
import { isTradable } from '../../shared/tradable';
import { ToolsContext } from '../tools-context';
import { Allocation, DonutSlice, buildAllocation } from './allocation';
import { AllocationDonutComponent } from './allocation-donut';
import { TargetAllocation } from './target-allocation';
import { TargetTableComponent } from './target-table';
import { TradePreview, previewTrades } from './trade-preview';
import { TradePreviewPanelComponent } from './trade-preview-panel';

/**
 * Rebalance Donut's workspace: the selected account's current allocation, priced live, with a
 * target allocation the client shapes by dragging the donut's outer ring or typing into the table
 * under it. Beside the donut, the trades those targets take are listed live, priced at the ask and
 * bid, and go into the trade plan tagged Rebalance. Stocks the account doesn't own can be added
 * from the symbol search, so rebalancing can diversify too.
 */
@Component({
  selector: 'app-rebalance-tool',
  imports: [CommonModule, AllocationDonutComponent, TargetTableComponent, TradePreviewPanelComponent, SymbolSearchComponent],
  template: `
    <div *ngIf="state() === 'ready'" class="toolbar">
      <div *ngIf="editable()" class="hint" data-testid="rebalance-hint">
        <span class="key"><span class="ring now" aria-hidden="true"></span>Inner ring: now</span>
        <span class="key"><span class="ring target" aria-hidden="true"></span>Outer ring: target</span>
        <span class="how">Drag a <span class="knob" aria-hidden="true"></span> handle to reshape. Hold Shift for 0.1% steps.</span>
      </div>

      <div #addStockBox class="add-stock">
        <button #addButton type="button" class="add" data-testid="add-stock" (click)="adding() ? closeAdd() : openAdd()"
                [attr.aria-expanded]="adding()" aria-controls="add-stock-panel">
          <svg viewBox="0 0 16 16" width="14" height="14" aria-hidden="true"><path d="M8 3v10M3 8h10" /></svg>
          Add stock
        </button>
        <div *ngIf="adding()" id="add-stock-panel" class="add-panel" role="dialog" aria-labelledby="add-stock-title"
             data-testid="add-stock-panel" (keydown.escape)="closeAdd(true)">
          <div class="add-head">
            <h2 id="add-stock-title">Add a stock</h2>
            <button type="button" class="close" aria-label="Close" (click)="closeAdd(true)">
              <svg viewBox="0 0 16 16" width="14" height="14" aria-hidden="true"><path d="M4 4l8 8M12 4l-8 8" /></svg>
            </button>
          </div>
          <p>It starts at a 5% target, taken evenly from your unlocked slices.</p>
          <app-symbol-search [livePrices]="context.prices()" [unavailable]="unavailable" [shortcut]="false"
                             placeholder="Search stocks to add" (picked)="addStock($event.symbol, $event.name)"></app-symbol-search>
        </div>
      </div>
    </div>

    <app-allocation-donut [allocation]="allocation()" [state]="state()" [errorMessage]="context.error()"
                          [targets]="editable() ? targets : null" [trades]="preview()?.bySymbol ?? null"
                          [showLegend]="!editable()"
                          (retry)="context.load()" (addHolding)="addHolding()">
      <app-trade-preview-panel donutAside *ngIf="preview() as trades" [preview]="trades"
                               [hasPlanned]="planned().length > 0" [inPlan]="inPlan()" (add)="addToPlan()">
      </app-trade-preview-panel>
    </app-allocation-donut>

    <app-target-table *ngIf="editable() && allocation() as current" [allocation]="current" [targets]="targets"
                      [preview]="preview()">
    </app-target-table>

    <div class="sr-only" aria-live="polite" role="status" data-testid="target-announcer">{{ targets.announcement() }}</div>
  `,
  styles: [`
    :host { display: block; --subtle: #a1a1ae; }
    .toolbar { display: flex; flex-wrap: wrap; align-items: center; gap: 10px 18px; margin-bottom: 8px; }
    .hint {
      display: flex;
      flex: 1;
      flex-wrap: wrap;
      align-items: center;
      gap: 6px 18px;
      font-size: 12px;
      color: var(--subtle);
    }
    .add-stock { position: relative; margin-left: auto; }
    .add {
      display: inline-flex;
      align-items: center;
      gap: 6px;
      padding: 7px 14px;
      border-radius: 999px;
      border: 1px solid color-mix(in srgb, var(--primary) 45%, var(--border));
      background: color-mix(in srgb, var(--primary) 10%, transparent);
      color: var(--foreground);
      font-size: 13px;
      font-weight: 600;
      transition: background 0.15s ease;
    }
    .add:hover, .add[aria-expanded='true'] { background: color-mix(in srgb, var(--primary) 20%, transparent); }
    .add svg, .close svg { fill: none; stroke: currentColor; stroke-width: 1.8; stroke-linecap: round; }
    .add-panel {
      position: absolute;
      top: calc(100% + 8px);
      right: 0;
      z-index: 5;
      width: min(380px, calc(100vw - 32px));
      padding: 14px;
      border-radius: var(--radius-lg);
      border: 1px solid var(--border);
      background: var(--card);
      box-shadow: 0 16px 40px rgb(0 0 0 / 0.5);
      animation: panel-in 0.16s ease-out;
    }
    .add-head { display: flex; align-items: center; justify-content: space-between; }
    .add-head h2 { font-size: 15px; font-weight: 600; color: var(--foreground); }
    .close { display: grid; place-items: center; width: 28px; height: 28px; border-radius: 8px; background: transparent; color: var(--subtle); }
    .close:hover { background: var(--surface-hover); color: var(--foreground); }
    .add-panel p { margin: 2px 0 12px; font-size: 13px; color: var(--subtle); }
    @keyframes panel-in { from { opacity: 0; transform: translateY(-4px); } }
    @media (prefers-reduced-motion: reduce) { .add-panel { animation: none; } .add { transition: none; } }
    .key { display: inline-flex; align-items: center; gap: 6px; }
    .ring { width: 12px; height: 12px; border-radius: 50%; border: 3px solid var(--subtle); }
    .ring.target { border-width: 2px; outline: 2px solid var(--subtle); outline-offset: 2px; width: 8px; height: 8px; }
    .how { margin-left: auto; }
    .knob {
      display: inline-block;
      width: 10px;
      height: 10px;
      margin: 0 2px -1px;
      border-radius: 50%;
      background: var(--foreground);
    }
    app-target-table { margin-top: 24px; }
    @media (max-width: 640px) {
      .how { margin-left: 0; }
      .add-stock { position: static; }
      .add-panel { left: 0; right: 0; width: auto; }
      .toolbar { position: relative; }
    }
  `],
  providers: [TargetAllocation]
})
export class RebalanceToolComponent {
  readonly context = inject(ToolsContext);
  readonly targets = inject(TargetAllocation);

  /** Whether the Add stock search is open. */
  readonly adding = signal(false);
  private readonly search = viewChild(SymbolSearchComponent);
  private readonly addStockRef = viewChild<ElementRef<HTMLElement>>('addStockBox');
  private readonly addButton = viewChild<ElementRef<HTMLButtonElement>>('addButton');

  /** Why a symbol can't be added: not tradable, already a slice, or held but not priced yet. */
  readonly unavailable = (symbol: string): string | null => {
    if (!isTradable(symbol)) {
      return 'Not tradable';
    }
    if (this.targets.keys().includes(symbol)) {
      return 'In donut';
    }
    return this.context.holdings().some(h => h.symbol === symbol) ? 'Owned' : null;
  };

  readonly state = computed<'loading' | 'error' | 'ready'>(() => {
    if (this.context.error()) {
      return 'error';
    }
    return this.context.loading() || !this.context.account() ? 'loading' : 'ready';
  });

  /**
   * Every open position at its live price, plus cash. Recomputes as prices tick. Cash keeps a
   * slice even at $0, so the client can still choose to hold some.
   */
  private readonly current = computed<Allocation | null>(() => {
    const account = this.context.account();
    if (!account) {
      return null;
    }
    const holdings = this.context.holdings().map(h => ({ symbol: h.symbol, name: h.name, quantity: h.quantity, price: h.price }));
    const added = this.targets.added().filter(a => !holdings.some(h => h.symbol === a.symbol));
    const base = buildAllocation(holdings, account.cash, { alwaysCash: holdings.length + added.length > 0 });
    // Added stocks are slices worth nothing yet, just before cash, so they line up with their targets.
    const newSlices: DonutSlice[] = added.map(a => ({
      key: a.symbol, kind: 'holding', label: a.symbol, name: a.name, shares: 0, value: 0, share: 0, color: '',
      grouped: false, added: true
    }));
    return {
      ...base,
      slices: [...base.slices.filter(s => s.kind === 'holding'), ...newSlices, ...base.slices.filter(s => s.kind === 'cash')]
    };
  });

  /** The current allocation in the targets' fixed ring order, so the two rings line up. */
  readonly allocation = computed(() => this.targets.order(this.current()));

  /** Targets need something to reshape: at least one holding. */
  readonly editable = computed(() => this.state() === 'ready' && (this.current()?.slices.some(s => s.kind === 'holding') ?? false));

  /**
   * The trades the targets take, from the order-plan engine. Recomputed straight away on every
   * change, drag steps included, and as prices tick.
   */
  readonly preview = computed<TradePreview | null>(() => {
    const account = this.context.account();
    const allocation = this.allocation();
    if (!this.editable() || !account || !allocation) {
      return null;
    }
    const keys = this.targets.keys();
    const values = this.targets.targets();
    return previewTrades({
      slices: allocation.slices,
      targets: Object.fromEntries(keys.map((key, i) => [key, values[i]])),
      positions: this.context.holdings().map(h => ({ symbol: h.symbol, quantity: h.quantity })),
      prices: this.context.prices(),
      quoteOffsets: this.context.quoteOffsets(),
      cash: account.cash
    });
  });

  /** The Rebalance trades already in the tray, for this account. */
  readonly planned = computed(() => {
    const plan = this.context.plan;
    return plan.accountId() === this.context.account()?.accountId ? plan.orders().filter(o => o.source === 'rebalance') : [];
  });

  /** Whether the tray already holds exactly these trades. */
  readonly inPlan = computed(() => {
    const orders = this.preview()?.orders ?? [];
    const planned = this.planned();
    const key = (o: { side: string; symbol: string; quantity: number }) => `${o.side} ${o.symbol} ${o.quantity}`;
    const inTray = new Set(planned.map(key));
    return orders.length > 0 && orders.length === planned.length && orders.every(o => inTray.has(key(o)));
  });

  constructor() {
    effect(() => this.targets.sync(this.current(), this.context.account()?.accountId ?? null));
    // Market orders fill at the ask or bid, so the preview prices every holding (and added stock) at those.
    effect(() => {
      const symbols = [...this.context.holdings().map(h => h.symbol), ...this.targets.added().map(a => a.symbol)];
      untracked(() => this.context.requestQuotes(symbols));
    });
    // Added stocks aren't held, so the tools page wouldn't stream their prices otherwise.
    effect(() => {
      const symbols = this.targets.added().map(a => a.symbol);
      untracked(() => this.context.toolSymbols.set(symbols));
    });
    inject(DestroyRef).onDestroy(() => this.context.toolSymbols.set([]));
  }

  openAdd(): void {
    this.adding.set(true);
    // The search renders with the panel, so it's focused once it's there.
    setTimeout(() => this.search()?.focus());
  }

  /** Closes the search; `refocus` puts focus back on the button, for Escape and the close button. */
  closeAdd(refocus = false): void {
    this.adding.set(false);
    if (refocus) {
      this.addButton()?.nativeElement.focus();
    }
  }

  /** A click outside the open search closes it. */
  @HostListener('document:pointerdown', ['$event'])
  onDocumentPointer(event: PointerEvent): void {
    if (this.adding() && !this.addStockRef()?.nativeElement.contains(event.target as Node)) {
      this.closeAdd();
    }
  }

  /** A symbol picked in the search: added as a new slice unless it can't be. */
  addStock(symbol: string, name: string | null): void {
    if (this.unavailable(symbol)) {
      return;
    }
    this.targets.add(symbol, name ?? symbol);
    this.closeAdd(true);
  }

  /** Puts the trades in the tray tagged Rebalance, replacing any Rebalance trades already there. */
  addToPlan(): void {
    const account = this.context.account();
    const orders = this.preview()?.orders ?? [];
    if (!account || !orders.length) {
      return;
    }
    this.context.plan.addPlan('rebalance', account.accountId,
      orders.map(o => ({ symbol: o.symbol, side: o.side, quantity: o.quantity })));
  }

  /** The empty account's prompt opens the same search. */
  addHolding(): void {
    this.openAdd();
  }
}
