import { AfterViewInit, Component, DestroyRef, ElementRef, EventEmitter, OnInit, Output, computed, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { OrderService } from '../services/order';
import { MarketStatusService } from '../services/market-status';
import { CONCENTRATION_WARNING, concentrationWarning } from '../shared/order-impact';
import { holdsByAccount } from '../shared/order-types';
import { formatMoney, formatSignedMoney } from '../shared/format';
import { ToolsContext } from './tools-context';
import { TradePlanService } from './trade-plan';
import { ConfirmedOrder, PlanSubmission, SubmissionStatus } from './plan-submission';

const STATUS_LABELS: Record<SubmissionStatus, string> = {
  waiting: 'Waiting',
  submitting: 'Submitting…',
  filled: 'Filled',
  accepted: 'Accepted (settling)',
  open: 'Open',
  rejected: 'Rejected',
  skipped: 'Skipped',
  unknown: 'Unknown'
};

/**
 * Trading Tools: one confirmation sheet for the whole trade plan. It lists every order at live
 * prices with the totals, warnings and price protection that apply, then submits them all - sells
 * first - showing each order's result as it comes back.
 */
@Component({
    selector: 'app-plan-review',
    imports: [CommonModule],
    template: `
    <div class="backdrop" (click)="close()"></div>
    <section class="sheet" role="dialog" aria-modal="true" aria-labelledby="review-title"
             (keydown.escape)="close()" data-testid="review-sheet">
      <header>
        <h2 id="review-title">{{ phase() === 'done' ? 'Plan submitted' : 'Review plan' }}</h2>
        <span *ngIf="context.planAccount() as account" class="account">{{ account.name }}</span>
      </header>

      <ng-container *ngIf="phase() === 'idle'; else progress">
        <p *ngIf="!regularHours()" class="notice" data-testid="session-notice">
          The market isn't in regular hours, so these orders will wait for the next open. Sales won't fund
          buys until then, and price protection is replaced by the open's price collar.
        </p>

        <table class="orders">
          <thead><tr><th>Side</th><th>Symbol</th><th class="n">Shares</th><th class="n">Est. price</th><th class="n">Value</th></tr></thead>
          <tbody>
            <tr *ngFor="let order of projection()?.orders ?? []" [attr.data-symbol]="order.symbol">
              <td [attr.data-side]="order.side">{{ order.side === 'BUY' ? 'Buy' : 'Sell' }}</td>
              <td class="symbol">{{ order.symbol }}</td>
              <td class="n num">{{ order.quantity }}</td>
              <td class="n num">{{ money(order.quotedPrice) }}</td>
              <td class="n num">{{ money(order.estimatedValue) }}</td>
            </tr>
          </tbody>
        </table>

        <dl class="totals" data-testid="review-totals">
          <div><dt>Cash spent</dt><dd class="num">{{ money(spent()) }}</dd></div>
          <div><dt>Cash raised</dt><dd class="num">{{ money(raised()) }}</dd></div>
          <div><dt>Net cash change</dt><dd class="num">{{ signed(projection()?.cashImpact) }}</dd></div>
          <div><dt>Cash after</dt><dd class="num">{{ money(projection()?.cashAfter) }}</dd></div>
        </dl>

        <p class="protection" data-testid="review-protection">
          Price protection:
          <strong>{{ maxSlippage() === null ? 'Off' : maxSlippage() + '%' }}</strong>
          <span *ngIf="maxSlippage() !== null"> on every order - one that would fill further than this from its
            price here is rejected.</span>
          <span *ngIf="maxSlippage() === null"> - turn it on in Accounts to reject fills that move too far.</span>
        </p>

        <p *ngFor="let message of concentration()" class="warning" data-warning="concentration">{{ message }}</p>
        <p *ngFor="let warning of projection()?.warnings ?? []" class="warning" [attr.data-warning]="warning.code">{{ warning.message }}</p>
        <p *ngIf="holdsError()" class="warning" role="alert">{{ holdsError() }}</p>
      </ng-container>

      <ng-template #progress>
        <ol class="results" aria-live="polite">
          <li *ngFor="let row of submission.rows()" [attr.data-status]="row.status" [attr.data-symbol]="row.symbol">
            <span class="what">{{ row.side === 'BUY' ? 'Buy' : 'Sell' }} {{ row.quantity }} {{ row.symbol }}</span>
            <span class="status">{{ statusLabel(row.status) }}</span>
            <span *ngIf="row.message" class="reason">{{ row.message }}</span>
          </li>
        </ol>
        <p *ngIf="phase() === 'done'" class="summary" role="status" data-testid="review-summary">{{ summaryText() }}</p>
      </ng-template>

      <footer>
        <ng-container *ngIf="phase() === 'idle'">
          <button type="button" class="secondary" (click)="close()">Cancel</button>
          <button type="button" class="primary" [disabled]="!!blocker()" (click)="submit()" data-testid="submit">
            Submit {{ count() }} order{{ count() === 1 ? '' : 's' }}
          </button>
        </ng-container>
        <button *ngIf="phase() !== 'idle'" type="button" class="primary" [disabled]="submission.busy()"
                (click)="close()" data-testid="done">
          {{ submission.busy() ? 'Submitting…' : 'Done' }}
        </button>
      </footer>
      <p *ngIf="phase() === 'idle' && blocker() as reason" class="blocker" data-testid="blocker">{{ reason }}</p>
    </section>
  `,
    styles: [`
    :host { position: fixed; inset: 0; z-index: 40; display: flex; align-items: flex-end; justify-content: center; }
    .backdrop { position: absolute; inset: 0; background: color-mix(in srgb, var(--background) 72%, transparent); }
    .sheet {
      position: relative;
      width: min(100%, 640px);
      max-height: min(88vh, 760px);
      overflow-y: auto;
      margin: 0 16px 16px;
      padding: 20px;
      border-radius: var(--radius-lg);
      border: 1px solid var(--border);
      background: var(--card);
      display: flex;
      flex-direction: column;
      gap: 14px;
    }
    header { display: flex; align-items: baseline; justify-content: space-between; gap: 12px; }
    h2 { font-size: 18px; font-weight: 600; color: var(--foreground); }
    .account { font-size: 13px; color: var(--muted-foreground); }
    .notice, .warning, .blocker {
      padding: 8px 12px;
      border-radius: var(--radius-md);
      font-size: 13px;
      line-height: 1.45;
    }
    .notice { background: var(--info-muted); color: var(--info); }
    .warning { background: var(--warning-muted); color: var(--warning); }
    .blocker { color: var(--muted-foreground); padding: 0; text-align: right; }
    table { width: 100%; border-collapse: collapse; font-size: 13px; }
    th { text-align: left; font-weight: 500; color: var(--muted-foreground); padding: 0 6px 6px 0; }
    td { padding: 6px 6px 6px 0; border-top: 1px solid var(--border); color: var(--foreground); }
    .n { text-align: right; }
    td[data-side='BUY'] { color: var(--gain); font-weight: 600; }
    td[data-side='SELL'] { color: var(--loss); font-weight: 600; }
    .symbol { font-weight: 600; }
    .totals { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 6px 20px; font-size: 13px; }
    .totals div { display: flex; justify-content: space-between; gap: 8px; }
    dt { color: var(--muted-foreground); }
    dd { color: var(--foreground); font-weight: 600; }
    .protection { font-size: 13px; color: var(--muted-foreground); }
    .protection strong { color: var(--foreground); }
    .results { list-style: none; display: flex; flex-direction: column; gap: 6px; font-size: 13px; }
    .results li { display: grid; grid-template-columns: 1fr auto; gap: 2px 12px; padding: 8px 0; border-top: 1px solid var(--border); }
    .what { color: var(--foreground); font-weight: 600; }
    .status { color: var(--muted-foreground); }
    li[data-status='filled'] .status, li[data-status='accepted'] .status { color: var(--gain); }
    li[data-status='open'] .status { color: var(--info); }
    li[data-status='rejected'] .status, li[data-status='unknown'] .status { color: var(--loss); }
    li[data-status='skipped'] .status { color: var(--warning); }
    .reason { grid-column: 1 / -1; color: var(--muted-foreground); font-size: 12px; }
    .summary { font-size: 14px; font-weight: 600; color: var(--foreground); }
    footer { display: flex; justify-content: flex-end; gap: 8px; }
    footer button { padding: 9px 16px; border-radius: 999px; font-size: 13px; font-weight: 600; }
    .primary { background: var(--primary); color: var(--primary-foreground); }
    .primary:disabled { opacity: 0.5; cursor: not-allowed; }
    .secondary { background: var(--muted); color: var(--foreground); }
    @media (max-width: 640px) {
      .sheet { margin: 0; width: 100%; max-height: 92vh; border-radius: var(--radius-lg) var(--radius-lg) 0 0; }
      .totals { grid-template-columns: minmax(0, 1fr); }
    }
  `]
})
export class PlanReviewComponent implements OnInit, AfterViewInit {
  /** Asks the page to close the sheet; it only does once nothing is being sent. */
  @Output() closed = new EventEmitter<void>();

  readonly plan = inject(TradePlanService);
  readonly context = inject(ToolsContext);
  readonly submission = inject(PlanSubmission);
  private readonly orderService = inject(OrderService);
  private readonly market = inject(MarketStatusService);
  private readonly destroyRef = inject(DestroyRef);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);

  readonly phase = this.submission.phase;
  readonly projection = this.context.planProjection;
  readonly maxSlippage = this.context.planMaxSlippage;
  readonly count = this.plan.count;

  /** Cash the account's open orders already hold back; null until they've loaded. */
  readonly heldCash = signal<number | null>(null);
  readonly holdsError = signal<string | null>(null);

  /** Only during regular hours do market orders fill at once; an unknown session isn't assumed open. */
  readonly regularHours = computed(() => this.market.session() === 'REGULAR');

  readonly spent = computed(() => this.total('BUY'));
  readonly raised = computed(() => this.total('SELL'));

  /** "AAPL would be 38% of your portfolio" for each symbol the plan buys into past the threshold. */
  readonly concentration = computed(() => {
    const projection = this.projection();
    if (!projection) {
      return [];
    }
    const bought = new Set(projection.orders.filter(o => o.side === 'BUY').map(o => o.symbol));
    return projection.allocationAfter
      .filter(h => bought.has(h.symbol) && h.share >= CONCENTRATION_WARNING)
      .map(h => concentrationWarning(h.symbol, h.share));
  });

  /** Why Submit is off, or null when it can go. */
  readonly blocker = computed(() => {
    const projection = this.projection();
    if (this.plan.isEmpty() || !projection) {
      return 'Your plan is empty.';
    }
    if (this.context.pricesLoading() || this.heldCash() === null && !this.holdsError()) {
      return 'Loading prices…';
    }
    if (projection.warnings.some(w => w.code === 'no-price')) {
      return 'Some orders have no price yet.';
    }
    if (projection.warnings.some(w => w.code === 'oversell')) {
      return 'The plan sells more shares than the account holds.';
    }
    return null;
  });

  readonly summaryText = computed(() => {
    const s = this.submission.summary();
    const parts = [
      [s.filled + s.accepted, 'filled'], [s.open, 'open'], [s.rejected, 'rejected'], [s.skipped, 'skipped'], [s.unknown, 'unknown']
    ] as const;
    const said = parts.filter(([n]) => n > 0).map(([n, label]) => `${n} ${label}`);
    const settling = s.accepted ? ` (${s.accepted} still settling)` : '';
    return (said.join(' · ') || 'Nothing was sent') + settling + '.';
  });

  money = formatMoney;
  signed = formatSignedMoney;

  ngOnInit(): void {
    this.submission.reset();
    this.orderService.getOpenOrders()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: orders => this.heldCash.set(holdsByAccount(orders)[this.plan.accountId() ?? '']?.cash ?? 0),
        // Without them, assume nothing is held; the server still refuses what can't be afforded.
        error: () => {
          this.heldCash.set(0);
          this.holdsError.set('Couldn’t check your open orders, so cash they hold isn’t counted here.');
        }
      });
  }

  ngAfterViewInit(): void {
    this.host.nativeElement.querySelector<HTMLButtonElement>('footer button')?.focus();
  }

  statusLabel(status: SubmissionStatus): string {
    return STATUS_LABELS[status];
  }

  /**
   * Sends the plan at the prices on screen now. The submission refuses a second start, and the
   * button is gone once it has begun, so a double click sends nothing twice.
   */
  async submit(): Promise<void> {
    const projection = this.projection();
    const account = this.context.planAccount();
    if (this.blocker() || !projection || !account || this.phase() !== 'idle') {
      return;
    }
    const sorted = this.plan.sorted();
    const confirmed: ConfirmedOrder[] = projection.orders.map((order, i) => ({
      id: sorted[i].id, symbol: order.symbol, side: order.side, quantity: order.quantity, quotedPrice: order.quotedPrice
    }));
    await this.submission.submit(account.accountId, confirmed, account.cash - (this.heldCash() ?? 0));
    this.plan.removeMany(this.submission.placedIds());
    // Holdings, cash and every preview reload from the account's new state.
    this.context.load();
  }

  close(): void {
    if (!this.submission.busy()) {
      this.closed.emit();
    }
  }

  private total(side: 'BUY' | 'SELL'): number {
    return (this.projection()?.orders ?? []).filter(o => o.side === side).reduce((sum, o) => sum + o.estimatedValue, 0);
  }
}
