import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';

export interface ExecutionItem {
  executionId: string;
  quantity: number;
  price: number;
  executedAt: string;
}

export interface OrderHistoryItem {
  orderId: string;
  symbol: string;
  side: string;
  quantity: number;
  status: string;
  submittedAt: string;
  filledAt: string | null;
  execution?: ExecutionItem | null;
  executions: ExecutionItem[];
}

export interface OrderHistoryPage {
  content: OrderHistoryItem[];
  number: number;
  size: number;
  totalElements: number;
  totalPages: number;
  first: boolean;
  last: boolean;
}

export interface OrderSubmissionRequest {
  accountId: string;
  symbol?: string;
  instrumentId?: string;
  side: 'BUY' | 'SELL';
  quantity: number;
  price?: number;
}

export interface OrderSubmissionResponse {
  orderId: string;
  accountId: string;
  accountNumber: string;
  instrumentId: string;
  symbol: string;
  side: string;
  quantity: number;
  status: string;
  submittedAt: string;
  acceptedAt?: string;
  rejectedAt?: string;
  filledAt?: string;
  rejectionReason?: string;
  execution?: ExecutionItem;
  accountBalanceAfter: number;
}

@Injectable({
  providedIn: 'root'
})
export class OrderService {
  // Same-origin path, proxied to order-app - see proxy.conf.js.
  private readonly API_URL = '/api/order';

  constructor(private http: HttpClient) {}

  getOrderHistory(page: number = 0, size: number = 20): Observable<OrderHistoryPage> {
    return this.http.get<OrderHistoryPage>(
      `${this.API_URL}/orders/history`,
      {
        params: {
          page: page.toString(),
          size: size.toString()
        }
      }
    );
  }

  submitOrder(request: OrderSubmissionRequest): Observable<OrderSubmissionResponse> {
    return this.http.post<OrderSubmissionResponse>(`${this.API_URL}/orders`, request);
  }
}
