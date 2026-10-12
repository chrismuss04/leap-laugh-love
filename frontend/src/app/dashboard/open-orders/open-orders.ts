import { Component, EventEmitter, Output, computed, inject, input, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import { OpenOrder, OrderService } from '../../services/order';
import { formatMoney } from '../../shared/format';
import { QuoteOffset, distanceTo, openOrderTitle, quoteSide, requiredMove, waitingPrice } from '../../shared/order-types';

/** The price is "near" its trigger inside this distance; the bar fills as it closes in. */
const NEAR_PERCENT = 10;

interface OpenOrderRow {
  order: OpenOrder;
  title: string;
  target: number | null;
  /** "Ask $462.95 · needs to fall 0.03%", "Ask at $462.80 · filling"... */
  status: string;
  /** 0 (far) to 1 (at the price), for the proximity bar. */
  proximity: number;
  account: string | null;
}

/**
 * The client's open orders - limit and stop orders waiting for their price - with how far the
 * market has to move for each and a two-step cancel. Hidden while there are none.
 */
@Component({
  selector: 'app-open-orders',
  imports: [CommonModule],
  template: `
    <section *ngIf="rows().length > 0" class="card fade-in" aria-labelledby="open-orders-title" data-testid="open-orders">
      <header class="card-header">
        <h2 id="open-orders-title">Open orders</h2>
        <span class="count num">{{ rows().length }}</span>
      </header>

      <p *ngIf="error()" class="error" role="alert">{{ error() }}</p>

      <ol class="rows">
        <li *ngFor="let row of rows(); trackBy: trackById" class="row" data-testid="open-order">
          <div class="top">
            <span class="identity">
              <span class="symbol">{{ row.order.symbol }}</span>
              <span class="title" [class.buy]="row.order.side === 'BUY'" [class.sell]="row.order.side === 'SELL'">
                {{ row.title }} · {{ row.order.quantity | number }}
              </span>
            </span>
            <span class="target num">{{ money(row.target) }}</span>
          </div>

          <div class="progress" role="meter" aria-valuemin="0" aria-valuemax="100"
               [attr.aria-valuenow]="round(row.proximity * 100)" [attr.aria-label]="row.status">
            <span class="bar" [style.width.%]="row.proximity * 100" [class.near]="row.proximity > 0.8"></span>
          </div>

          <div class="bottom">
            <span class="sub num">{{ row.status }}</span>
            <ng-container *ngIf="confirming() !== row.order.orderId; else confirm">
              <button type="button" class="cancel" (click)="confirming.set(row.order.orderId)"
                      [disabled]="cancelling() === row.order.orderId"
                      [attr.aria-label]="'Cancel ' + row.title + ' for ' + row.order.symbol">Cancel</button>
            </ng-container>
            <ng-template #confirm>
              <span class="confirm">
                <button type="button" class="keep" (click)="confirming.set(null)">Keep</button>
                <button type="button" class="cancel danger" (click)="cancel(row.order)"
                        [disabled]="cancelling() === row.order.orderId">
                  {{ cancelling() === row.order.orderId ? 'Cancelling…' : 'Cancel order' }}
                </button>
              </span>
            </ng-template>
          </div>

          <div class="meta">
            <span>{{ row.order.timeInForce === 'GTC' ? 'Until cancelled' : 'Good for today' }}</span>
            <span *ngIf="row.order.reservedCash"> · {{ money(row.order.reservedCash) }} set aside</span>
            <span *ngIf="row.account"> · {{ row.account }}</span>
          </div>
        </li>
      </ol>
    </section>
  `,
  styles: [`
    .card { background: var(--surface); border: 1px solid var(--border); border-radius: var(--radius-lg); }
    .card-header { display: flex; align-items: center; gap: 8px; padding: 18px 20px 10px; }
    h2 { font-size: 16px; font-weight: 600; }
    .count {
      font-size: 11px; font-weight: 700; min-width: 20px; padding: 2px 7px; border-radius: 999px;
      background: color-mix(in srgb, var(--primary) 14%, transparent); color: var(--primary); text-align: center;
    }
    .rows { list-style: none; display: flex; flex-direction: column; }
    .row { display: flex; flex-direction: column; gap: 8px; padding: 14px 20px; border-top: 1px solid var(--border); }
    .top, .bottom { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
    .identity { display: flex; align-items: baseline; gap: 8px; min-width: 0; }
    .symbol { font-weight: 600; font-size: 14px; }
    .title { font-size: 13px; font-weight: 500; white-space: nowrap; }
    .title.buy { color: var(--gain); }
    .title.sell { color: var(--loss); }
    .target { font-size: 14px; font-weight: 600; }
    .progress { height: 4px; border-radius: 999px; background: var(--muted); overflow: hidden; }
    .bar {
      display: block; height: 100%; border-radius: inherit; background: var(--muted-foreground);
      transition: width 0.6s ease, background 0.3s ease;
    }
    .bar.near { background: var(--primary); }
    .sub { font-size: 12px; color: var(--muted-foreground); }
    .meta { font-size: 11.5px; color: var(--muted-foreground); }
    .confirm { display: inline-flex; gap: 6px; }
    .cancel, .keep {
      padding: 4px 10px; border-radius: 999px; font-size: 12px; font-weight: 600;
      background: transparent; color: var(--muted-foreground); border: 1px solid var(--border);
    }
    .cancel:hover:not(:disabled), .keep:hover { color: var(--foreground); border-color: var(--muted-foreground); }
    .cancel.danger { background: var(--loss-muted); color: var(--loss); border-color: transparent; }
    .cancel:disabled { opacity: 0.6; cursor: progress; }
    .cancel:focus-visible, .keep:focus-visible { outline: 2px solid var(--primary); outline-offset: 2px; }
    .error { margin: 0 20px 10px; font-size: 13px; color: var(--loss); }
    @media (prefers-reduced-motion: reduce) { .bar { transition: none; } }
  `]
})
export class OpenOrdersComponent {
  readonly orders = input<OpenOrder[]>([]);
  /** Live last-trade prices by symbol. */
  readonly prices = input<Record<string, number>>({});
  /**
   * Each symbol's ask and bid offsets from its last trade. An order fills at the ask (buy) or bid
   * (sell), not the last trade, so distances are measured from last + offset.
   */
  readonly quoteOffsets = input<Record<string, QuoteOffset>>({});
  /** Account numbers by account ID, shown when the client has more than one account. */
  readonly accountNumbers = input<Record<string, string>>({});
  @Output() cancelled = new EventEmitter<OpenOrder>();

  private readonly orderService = inject(OrderService);

  readonly confirming = signal<string | null>(null);
  readonly cancelling = signal<string | null>(null);
  readonly error = signal<string | null>(null);

  money = formatMoney;
  round = Math.round;

  readonly rows = computed<OpenOrderRow[]>(() => {
    const prices = this.prices();
    const offsets = this.quoteOffsets();
    const accounts = this.accountNumbers();
    const multiAccount = Object.keys(accounts).length > 1;
    return this.orders().map(order => {
      const target = waitingPrice(order);
      const last = prices[order.symbol] ?? null;
      const offset = offsets[order.symbol];
      // The price this order fills at; the last trade until its quote has been seen.
      const price = last == null ? null : last + (offset ? (order.side === 'BUY' ? offset.ask : offset.bid) : 0);
      const distance = distanceTo(target, price);
      const side = quoteSide(order.side);
      // Reached once the price is at the target or past it in the direction the order waits for.
      const reached = !!distance && (distance.percent === 0 || distance.direction !== requiredMove(order));
      let status: string;
      if (!distance) {
        status = 'Waiting for a live price';
      } else if (reached) {
        status = offset ? `${side} at ${formatMoney(price)} · filling` : 'At its price · filling';
      } else {
        const digits = distance.percent < 1 ? 2 : 1;
        // Without a quote yet, the distance is from the last trade, so it isn't labelled as the ask or bid.
        status = offset
          ? `${side} ${formatMoney(price)} · needs to ${distance.direction} ${distance.percent.toFixed(digits)}%`
          : `${order.symbol} needs to ${distance.direction} ${distance.percent.toFixed(digits)}%`;
      }
      if (order.orderType === 'STOP_LIMIT' && order.triggeredAt) {
        status = `Stop reached · ${status}`;
      }
      return {
        order,
        title: openOrderTitle(order),
        target,
        status,
        proximity: !distance ? 0 : reached ? 1 : Math.max(0, 1 - distance.percent / NEAR_PERCENT),
        account: multiAccount ? accounts[order.accountId] ?? null : null
      };
    });
  });

  cancel(order: OpenOrder): void {
    this.cancelling.set(order.orderId);
    this.error.set(null);
    this.orderService.cancelOrder(order.orderId).subscribe({
      next: () => {
        this.cancelling.set(null);
        this.confirming.set(null);
        this.cancelled.emit(order);
      },
      error: (err: HttpErrorResponse) => {
        this.cancelling.set(null);
        this.confirming.set(null);
        // 409: it filled (or expired) first; the reload shows where it went.
        this.error.set(err.status === 409
          ? err.error?.message ?? 'This order is no longer open.'
          : 'We couldn’t cancel this order. Please try again.');
        if (err.status === 409) {
          this.cancelled.emit(order);
        }
      }
    });
  }

  trackById(_: number, row: OpenOrderRow): string {
    return row.order.orderId;
  }
}
