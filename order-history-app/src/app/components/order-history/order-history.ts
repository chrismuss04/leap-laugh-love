import { Component, inject, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { Router } from '@angular/router';
import { AuthService } from '../../services/auth';
import { OrderService, OrderHistoryItem, OrderHistoryPage } from '../../services/order';

@Component({
  selector: 'app-order-history',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './order-history.html',
  styleUrl: './order-history.css'
})
export class OrderHistoryComponent implements OnInit {
  orders: OrderHistoryItem[] = [];
  currentPage = 0;
  totalPages = 0;
  loading = false;
  error: string | null = null;
  expandedOrderId: string | null = null;

  private authService = inject(AuthService);
  private orderService = inject(OrderService);
  private router = inject(Router);

  ngOnInit() {
    if (!this.authService.isAuthenticated()) {
      this.router.navigate(['/login']);
      return;
    }
    this.loadOrders();
  }

  loadOrders(page: number = 0) {
    const clientId = this.authService.getClientId();
    if (!clientId) {
      this.error = 'Client ID not found';
      return;
    }

    this.loading = true;
    this.error = null;
    this.expandedOrderId = null;

    this.orderService.getOrderHistory(clientId, page, 20).subscribe({
      next: (response: OrderHistoryPage) => {
        this.orders = response.content;
        this.currentPage = response.number;
        this.totalPages = response.totalPages;
        this.loading = false;
      },
      error: (err) => {
        this.error = err.error?.message || 'Failed to load orders';
        this.loading = false;
      }
    });
  }

  toggleExpand(orderId: string) {
    this.expandedOrderId = this.expandedOrderId === orderId ? null : orderId;
  }

  nextPage() {
    if (this.currentPage < this.totalPages - 1) {
      this.loadOrders(this.currentPage + 1);
    }
  }

  prevPage() {
    if (this.currentPage > 0) {
      this.loadOrders(this.currentPage - 1);
    }
  }

  logout() {
    this.authService.logout();
    this.router.navigate(['/login']);
  }
}

