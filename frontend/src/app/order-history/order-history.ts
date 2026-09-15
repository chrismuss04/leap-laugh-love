import { Component, inject, OnInit, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { AuthService } from '../services/auth.service';
import { OrderService, OrderHistoryItem, OrderHistoryPage } from '../services/order';

@Component({
  selector: 'app-order-history',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './order-history.html',
  styleUrl: './order-history.css'
})
export class OrderHistoryComponent implements OnInit {
  orders = signal<OrderHistoryItem[]>([]);
  currentPage = signal(0);
  totalPages = signal(0);
  loading = signal(false);
  error = signal<string | null>(null);
  expandedOrderId = signal<string | null>(null);

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

    this.orderService.getOrderHistory(page, 20).subscribe({
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

