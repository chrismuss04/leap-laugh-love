import { Component, Input } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterLink } from '@angular/router';
import { OrderHistoryItem } from '../../services/order';
import { formatMoney } from '../../shared/format';

/** The client's latest orders, with a link through to the full order history page. */
@Component({
    selector: 'app-recent-activity',
    imports: [CommonModule, RouterLink],
    template: `
    <section class="card" aria-labelledby="activity-title">
      <header class="card-header">
        <h2 id="activity-title">Recent activity</h2>
        <a routerLink="/orders" class="link">View all</a>
      </header>

      <div *ngIf="loading" class="rows" aria-busy="true">
        <div class="row" *ngFor="let _ of [1, 2, 3]"><span class="skeleton" style="width: 100%; height: 34px"></span></div>
      </div>

      <p *ngIf="!loading && error" class="empty">{{ error }}</p>
      <p *ngIf="!loading && !error && orders.length === 0" class="empty">No orders yet. Your trades will show up here.</p>

      <ol *ngIf="!loading && orders.length > 0" class="rows">
        <li class="row" *ngFor="let order of orders; trackBy: trackById">
          <span class="identity">
            <span class="symbol">{{ order.symbol }}</span>
            <span class="sub num">{{ order.submittedAt | date: 'MMM d, h:mm a' }}</span>
          </span>
          <span class="detail">
            <span class="side" [class.buy]="order.side === 'BUY'" [class.sell]="order.side === 'SELL'">
              {{ order.side === 'BUY' ? 'Buy' : 'Sell' }} {{ order.quantity | number }}
            </span>
            <span class="sub num">{{ fillText(order) }}</span>
          </span>
          <span class="status" [attr.data-status]="order.status">{{ order.status | titlecase }}</span>
        </li>
      </ol>
    </section>
  `,
    styles: [`
    .card { background: var(--surface); border: 1px solid var(--border); border-radius: var(--radius-lg); }
    .card-header { display: flex; align-items: baseline; justify-content: space-between; padding: 18px 20px 10px; }
    h2 { font-size: 16px; font-weight: 600; }
    .link { font-size: 13px; font-weight: 600; color: var(--primary); text-decoration: none; }
    .link:hover { text-decoration: underline; }
    .rows { list-style: none; display: flex; flex-direction: column; }
    .row { display: flex; align-items: center; gap: 16px; padding: 12px 20px; border-top: 1px solid var(--border); }
    .identity, .detail { display: flex; flex-direction: column; gap: 2px; min-width: 0; }
    .identity { width: 96px; flex-shrink: 0; }
    .detail { flex: 1; }
    .symbol { font-weight: 600; font-size: 14px; }
    .sub { font-size: 12px; color: var(--muted-foreground); }
    .side { font-size: 14px; font-weight: 500; }
    .side.buy { color: var(--gain); }
    .side.sell { color: var(--loss); }
    .status {
      font-size: 11px; font-weight: 600; padding: 3px 8px; border-radius: 999px;
      background: var(--muted); color: var(--muted-foreground);
    }
    .status[data-status='FILLED'] { background: var(--gain-muted); color: var(--gain); }
    .status[data-status='REJECTED'] { background: var(--loss-muted); color: var(--loss); }
    .status[data-status='SUBMITTED'], .status[data-status='ACCEPTED'] { background: var(--info-muted); color: var(--info); }
    .empty { padding: 20px; border-top: 1px solid var(--border); font-size: 14px; color: var(--muted-foreground); }
  `]
})
export class RecentActivityComponent {
  @Input() orders: OrderHistoryItem[] = [];
  @Input() loading = false;
  @Input() error: string | null = null;

  fillText(order: OrderHistoryItem): string {
    const fills = order.executions ?? [];
    const filled = fills.reduce((sum, fill) => sum + fill.quantity, 0);
    if (order.status === 'REJECTED') {
      return 'Not filled';
    }
    if (filled === 0) {
      return 'Market order';
    }
    const average = fills.reduce((sum, fill) => sum + fill.price * fill.quantity, 0) / filled;
    return `Market @ ${formatMoney(average)}`;
  }

  trackById(_: number, order: OrderHistoryItem): string {
    return order.orderId;
  }
}
