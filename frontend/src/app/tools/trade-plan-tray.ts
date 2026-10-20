import { Component, DestroyRef, ElementRef, EventEmitter, Output, ViewChild, computed, effect, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { PlanWarning, PlannedOrder } from '../shared/order-plan';
import { formatMoney, formatSignedMoney } from '../shared/format';
import { MiniDonutComponent } from './previews/mini-donut';
import { ToolsContext, allocationSlices } from './tools-context';
import { TradePlanService, TrayOrder, conditionsLabel, sourceLabel } from './trade-plan';

interface TrayRow {
  order: TrayOrder;
  priced: PlannedOrder;
}

/**
 * Trading Tools: the trade plan tray, docked to the bottom of every /tools page. Collapsed it
 * shows how many orders are planned and what they do to cash; expanded it lists them, flags
 * opposite orders on one symbol, and projects the account after every order fills - all valued
 * at live prices.
 */
@Component({
    selector: 'app-trade-plan-tray',
    imports: [CommonModule, MiniDonutComponent],
    template: `
    <!-- Announces adds, removals and netting; the visible tray doesn't change focus to say so. -->
    <div class="sr-only" aria-live="polite" role="status" data-testid="tray-announcer">{{ plan.announcement() }}</div>

    <section class="tray" [class.open]="expanded()" aria-label="Trade plan" (keydown.escape)="collapse()">
      <div *ngIf="expanded()" id="trade-plan-panel" class="panel" data-testid="tray-panel">
        <div class="panel-head">
          <h2>Trade plan<span *ngIf="context.planAccount() as account" class="for"> · {{ account.name }}</span></h2>
          <button *ngIf="!plan.isEmpty()" type="button" class="link danger" (click)="plan.clear()">Clear all</button>
        </div>

        <p *ngIf="plan.isEmpty()" class="empty">No orders yet. Add trades from any tool to review them here together.</p>

        <ul *ngIf="!plan.isEmpty()" class="orders" role="list">
          <li *ngFor="let row of rows(); trackBy: trackById" class="order" [attr.data-order]="row.order.id">
            <span class="side" [attr.data-side]="row.order.side">{{ row.order.side === 'BUY' ? 'Buy' : 'Sell' }}</span>
            <span class="symbol">{{ row.order.symbol }}</span>
            <label class="qty">
              <span class="sr-only">Shares of {{ row.order.symbol }}</span>
              <input type="number" inputmode="numeric" min="1" step="1" [value]="row.order.quantity"
                     [attr.aria-invalid]="errors()[row.order.id] ? true : null"
                     [attr.aria-describedby]="errors()[row.order.id] ? 'qty-error-' + row.order.id : null"
                     (change)="edit(row.order, $any($event.target))" />
            </label>
            <span class="value num">{{ money(row.priced.estimatedValue) }}</span>
            <span class="source">{{ label(row.order.source) }}</span>
            <button type="button" class="remove" (click)="plan.remove(row.order.id)"
                    [attr.aria-label]="'Remove ' + row.order.side.toLowerCase() + ' ' + row.order.symbol">×</button>
            <span *ngIf="terms(row.order) as term" class="terms" data-testid="tray-terms">{{ term }}</span>
            <span *ngIf="errors()[row.order.id] as error" class="error" role="alert" [id]="'qty-error-' + row.order.id">{{ error }}</span>
          </li>
        </ul>

        <div *ngFor="let symbol of plan.conflicts()" class="warning conflict" role="alert" [attr.data-conflict]="symbol">
          <span>The plan both buys and sells {{ symbol }}.</span>
          <button type="button" class="link" (click)="plan.net(symbol)">Net into one order</button>
        </div>
        <p *ngFor="let warning of warnings()" class="warning" [attr.data-warning]="warning.code">{{ warning.message }}</p>

        <div *ngIf="projection() as projected" class="after" data-testid="tray-projection">
          <app-mini-donut [slices]="slicesAfter()" [size]="72"></app-mini-donut>
          <dl>
            <div><dt>Cash after</dt><dd class="num">{{ money(projected.cashAfter) }}</dd></div>
            <div *ngFor="let holding of projected.allocationAfter">
              <dt>{{ holding.symbol }}</dt><dd class="num">{{ percent(holding.share) }}</dd>
            </div>
            <div><dt>Cash</dt><dd class="num">{{ percent(projected.cashShareAfter) }}</dd></div>
          </dl>
        </div>

        <div *ngIf="!plan.isEmpty()" class="panel-foot">
          <button type="button" class="review" (click)="review.emit()" data-testid="review-plan">Review plan</button>
        </div>
      </div>

      <button #toggle type="button" class="bar" (click)="toggleOpen()" [attr.aria-expanded]="expanded()"
              aria-controls="trade-plan-panel" data-testid="tray-toggle">
        <span class="badge" [class.bump]="bump()" aria-hidden="true">{{ plan.count() }}</span>
        <span class="summary" data-testid="tray-summary">{{ summary() }}</span>
        <span class="chevron" aria-hidden="true">{{ expanded() ? '▾' : '▴' }}</span>
      </button>
    </section>
  `,
    styles: [`
    :host {
      position: fixed;
      left: 0;
      right: 0;
      bottom: 0;
      z-index: 20;
      display: flex;
      justify-content: center;
      padding: 0 16px 12px;
      pointer-events: none;
    }
    .tray {
      pointer-events: auto;
      width: min(100%, 720px);
      border-radius: var(--radius-lg);
      border: 1px solid var(--border);
      background: var(--card);
      box-shadow: 0 -8px 32px rgba(0, 0, 0, 0.35);
      overflow: hidden;
    }
    .bar {
      display: flex;
      align-items: center;
      gap: 12px;
      width: 100%;
      padding: 12px 16px;
      background: var(--card);
      color: var(--foreground);
      font-size: 14px;
      font-weight: 600;
      text-align: left;
    }
    .bar:hover { background: var(--surface-hover); }
    .bar:focus-visible { outline: 2px solid var(--ring); outline-offset: -2px; }
    .badge {
      min-width: 24px;
      padding: 2px 8px;
      border-radius: 999px;
      background: var(--primary);
      color: var(--primary-foreground);
      font-size: 12px;
      text-align: center;
    }
    .badge.bump { animation: bump 0.55s cubic-bezier(0.2, 0.9, 0.3, 1.4); }
    @keyframes bump {
      0% { transform: scale(1); }
      35% { transform: scale(1.45); box-shadow: 0 0 0 6px color-mix(in srgb, var(--primary) 30%, transparent); }
      100% { transform: scale(1); box-shadow: 0 0 0 0 transparent; }
    }
    @media (prefers-reduced-motion: reduce) { .badge.bump { animation: none; } }
    .summary { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
    .chevron { color: var(--muted-foreground); }
    .panel {
      max-height: min(60vh, 520px);
      overflow-y: auto;
      padding: 16px;
      border-bottom: 1px solid var(--border);
      display: flex;
      flex-direction: column;
      gap: 12px;
    }
    .panel-head { display: flex; justify-content: space-between; align-items: baseline; gap: 12px; }
    h2 { font-size: 16px; font-weight: 600; color: var(--foreground); }
    .for { color: var(--muted-foreground); font-weight: 500; }
    .empty { font-size: 13px; color: var(--muted-foreground); }
    .orders { list-style: none; display: flex; flex-direction: column; gap: 6px; }
    .order {
      display: grid;
      grid-template-columns: 44px minmax(52px, 1fr) 76px minmax(84px, auto) minmax(64px, auto) 28px;
      align-items: center;
      gap: 8px;
      font-size: 13px;
    }
    .side { font-weight: 600; }
    .side[data-side='BUY'] { color: var(--gain); }
    .side[data-side='SELL'] { color: var(--loss); }
    .symbol { font-weight: 600; color: var(--foreground); }
    .qty input {
      width: 100%;
      padding: 4px 6px;
      border-radius: var(--radius-md);
      border: 1px solid var(--border);
      background: var(--surface);
      color: var(--foreground);
      font: inherit;
    }
    .qty input[aria-invalid='true'] { border-color: var(--loss); }
    .value { text-align: right; color: var(--foreground); }
    .source { color: var(--muted-foreground); font-size: 12px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
    .remove { background: none; color: var(--muted-foreground); font-size: 18px; line-height: 1; border-radius: var(--radius-md); }
    .remove:hover, .remove:focus-visible { color: var(--foreground); background: var(--surface-hover); }
    .error { grid-column: 1 / -1; color: var(--loss); font-size: 12px; }
    .terms { grid-column: 2 / -1; margin-top: -2px; color: var(--muted-foreground); font-size: 12px; }
    .warning {
      display: flex;
      justify-content: space-between;
      align-items: center;
      gap: 12px;
      padding: 8px 12px;
      border-radius: var(--radius-md);
      background: var(--warning-muted);
      color: var(--warning);
      font-size: 13px;
    }
    .link { background: none; color: var(--foreground); font-size: 13px; font-weight: 600; text-decoration: underline; white-space: nowrap; }
    .link.danger { color: var(--loss); }
    .after { display: flex; align-items: center; gap: 16px; padding-top: 4px; }
    .panel-foot { display: flex; justify-content: flex-end; }
    .review {
      padding: 9px 18px;
      border-radius: 999px;
      background: var(--primary);
      color: var(--primary-foreground);
      font-size: 13px;
      font-weight: 600;
    }
    dl { display: grid; grid-template-columns: repeat(auto-fill, minmax(110px, 1fr)); gap: 4px 16px; flex: 1; font-size: 13px; }
    dl div { display: flex; justify-content: space-between; gap: 8px; }
    dt { color: var(--muted-foreground); }
    dd { color: var(--foreground); }
    @media (max-width: 640px) {
      :host { padding: 0; }
      .tray { width: 100%; border-radius: var(--radius-lg) var(--radius-lg) 0 0; border-bottom: 0; }
      .panel { max-height: 70vh; }
      .order { grid-template-columns: 36px 1fr 64px auto 28px; }
      .source { grid-column: 2 / -1; grid-row: 2; }
      .after { flex-direction: column; align-items: stretch; }
    }
  `]
})
export class TradePlanTrayComponent {
  @ViewChild('toggle') private toggle?: ElementRef<HTMLButtonElement>;
  /** The client asked to review and submit the plan. */
  @Output() review = new EventEmitter<void>();

  readonly plan = inject(TradePlanService);
  readonly context = inject(ToolsContext);

  readonly expanded = signal(false);
  /** Set for a moment when orders arrive, so the badge pops - timed to land as Draw's chip does. */
  readonly bump = signal(false);
  private bumpTimer?: ReturnType<typeof setTimeout>;
  /** A refused quantity's message, per order. */
  readonly errors = signal<Record<string, string>>({});

  /** Every order valued at its live ask or bid, against the account the plan is for. */
  readonly projection = this.context.planProjection;

  readonly rows = computed<TrayRow[]>(() => {
    const priced = this.projection()?.orders ?? [];
    return this.plan.sorted().map((order, i) => ({
      order,
      priced: priced[i] ?? { ...order, quotedPrice: 0, estimatedValue: 0 }
    }));
  });

  readonly warnings = computed<PlanWarning[]>(() => this.projection()?.warnings ?? []);

  readonly slicesAfter = computed(() => {
    const projected = this.projection();
    return projected ? allocationSlices(projected.allocationAfter, projected.cashAfter) : [];
  });

  /** "4 orders · −$1,240.00 cash", or a prompt while the tray is empty. */
  readonly summary = computed(() => {
    const count = this.plan.count();
    if (!count) {
      return 'Trade plan · no orders yet';
    }
    const impact = this.projection()?.cashImpact;
    const orders = `${count} order${count === 1 ? '' : 's'}`;
    return impact == null ? orders : `${orders} · ${formatSignedMoney(impact)} cash`;
  });

  constructor() {
    // Only arrivals after the tray appears; it doesn't pop for orders already there.
    const seen = this.plan.arrivals();
    effect(() => {
      if (this.plan.arrivals() > seen) {
        clearTimeout(this.bumpTimer);
        this.bump.set(false);
        this.bumpTimer = setTimeout(() => {
          this.bump.set(true);
          this.bumpTimer = setTimeout(() => this.bump.set(false), 600);
        }, 650);
      }
    });
    inject(DestroyRef).onDestroy(() => clearTimeout(this.bumpTimer));
  }

  money = formatMoney;
  label = sourceLabel;
  terms = conditionsLabel;

  percent(share: number): string {
    return `${Math.round(share * 100)}%`;
  }

  toggleOpen(): void {
    this.expanded.update(open => !open);
  }

  /** Escape closes the tray and hands focus back to its toggle. */
  collapse(): void {
    if (this.expanded()) {
      this.expanded.set(false);
      this.toggle?.nativeElement.focus();
    }
  }

  edit(order: TrayOrder, input: HTMLInputElement): void {
    const error = this.plan.setQuantity(order.id, input.value === '' ? Number.NaN : Number(input.value));
    this.errors.update(current => {
      const { [order.id]: _, ...rest } = current;
      return error ? { ...rest, [order.id]: error } : rest;
    });
  }

  trackById(_: number, row: TrayRow): string {
    return row.order.id;
  }
}
