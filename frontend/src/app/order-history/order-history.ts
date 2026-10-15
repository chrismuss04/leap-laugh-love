import { Component, computed, inject, OnInit, signal, WritableSignal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { isQueuedOrder, orderTypeLabel, trailText } from '../shared/order-types';
import { formatMoney } from '../shared/format';
import { AuthService } from '../services/auth.service';
import { OrderService, OrderHistoryItem, OrderHistoryPage } from '../services/order';

@Component({
    selector: 'app-order-history',
    imports: [CommonModule],
    templateUrl: './order-history.html',
    styleUrl: './order-history.css'
})
export class OrderHistoryComponent implements OnInit {
  readonly orders = signal<OrderHistoryItem[]>([]);
  readonly currentPage = signal(0);
  readonly totalPages = signal(0);
  readonly loading = signal(false);
  readonly error = signal<string | null>(null);
  readonly expandedOrderId = signal<string | null>(null);

  readonly typeLabel = orderTypeLabel;
  /** A market order waiting for the open. */
  readonly queued = isQueuedOrder;

  /** The prices a limit or stop order was placed with, e.g. "stop $140.00 · limit $139.00". */
  conditionText(order: OrderHistoryItem): string | null {
    // A trailing stop's stop price is wherever it has followed the market to; what was chosen is the trail.
    const trailing = order.orderType === 'TRAILING_STOP';
    const parts = [
      trailing ? `trails ${trailText(order.trailAmount, order.trailPercent)}` : null,
      !trailing && order.stopPrice != null ? `stop ${formatMoney(order.stopPrice)}` : null,
      order.limitPrice != null ? `limit ${formatMoney(order.limitPrice)}` : null
    ].filter(Boolean);
    return parts.length ? parts.join(' · ') : null;
  }

  year = signal<number | null>(null);
  month = signal<number | null>(null);
  day = signal<number | null>(null);

  readonly years = Array.from({ length: 5 }, (_, i) => new Date().getFullYear() - i);
  readonly months = [
    'January', 'February', 'March', 'April', 'May', 'June',
    'July', 'August', 'September', 'October', 'November', 'December'
  ];
  days = computed(() => {
    const year = this.year();
    const month = this.month();
    if (year === null || month === null) {
      return [];
    }
    const daysInMonth = new Date(year, month, 0).getDate();
    return Array.from({ length: daysInMonth }, (_, i) => i + 1);
  });

  private authService = inject(AuthService);
  private orderService = inject(OrderService);

  ngOnInit() {
    if (!this.authService.isAuthenticated()) {
      return;
    }
    this.loadOrders();
  }

  loadOrders(page: number = 0) {
    this.loading.set(true);
    this.error.set(null);
    this.expandedOrderId.set(null);

    this.orderService.getOrderHistory(page, 20, this.year(), this.month(), this.day()).subscribe({
      next: (response: OrderHistoryPage) => {
        this.orders.set(response.content);
        this.currentPage.set(response.number);
        this.totalPages.set(response.totalPages);
        this.loading.set(false);
      },
      error: (err) => {
        this.error.set(err.error?.message || 'Failed to load orders');
        this.loading.set(false);
      }
    });
  }

  setFilter(field: WritableSignal<number | null>, event: Event) {
    const value = (event.target as HTMLSelectElement).value;
    field.set(value === '' ? null : Number(value));
    this.applyFilter();
  }

  clearFilter() {
    this.year.set(null);
    this.applyFilter();
  }

  // A month needs a year and a day needs a month; drop whatever a change made invalid.
  private applyFilter() {
    if (this.year() === null) {
      this.month.set(null);
    }
    if (this.month() === null) {
      this.day.set(null);
    }
    const day = this.day();
    if (day !== null && day > this.days().length) {
      this.day.set(null);
    }
    this.loadOrders(0);
  }

  toggleExpand(orderId: string) {
    this.expandedOrderId.set(this.expandedOrderId() === orderId ? null : orderId);
  }

  nextPage() {
    if (this.currentPage() < this.totalPages() - 1) {
      this.loadOrders(this.currentPage() + 1);
    }
  }

  prevPage() {
    if (this.currentPage() > 0) {
      this.loadOrders(this.currentPage() - 1);
    }
  }

  logout() {
    this.authService.logout();
  }
}

