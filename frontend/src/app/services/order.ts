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

export type OrderSide = 'BUY' | 'SELL';

/** A market order: omitting price lets order-app fill at the live ask (buy) or bid (sell). */
export interface OrderSubmissionRequest {
  accountId: string;
  symbol: string;
  side: OrderSide;
  quantity: number;
}

export interface OrderSubmissionResponse {
  orderId: string;
  accountId: string;
  accountNumber: string;
  symbol: string;
  side: OrderSide;
  quantity: number;
  status: 'SUBMITTED' | 'ACCEPTED' | 'REJECTED' | 'FILLED';
  submittedAt: string;
  filledAt: string | null;
  rejectionReason: string | null;
  execution: {
    executionId: string;
    fillQuantity: number | null;
    fillPrice: number | null;
    status: string;
    executedAt: string;
    reason: string | null;
  } | null;
  accountBalanceAfter: number | null;
}

@Injectable({
  providedIn: 'root'
})
export class OrderService {
  // Same-origin path, proxied to order-app - see proxy.conf.js.
  private readonly API_URL = '/api/order';

  constructor(private http: HttpClient) {}

  getOrderHistory(
    page: number = 0,
    size: number = 20,
    year: number | null = null,
    month: number | null = null,
    day: number | null = null
  ): Observable<OrderHistoryPage> {
    const params: Record<string, string> = {
      page: page.toString(),
      size: size.toString()
    };
    if (year !== null) params['year'] = year.toString();
    if (month !== null) params['month'] = month.toString();
    if (day !== null) params['day'] = day.toString();

    return this.http.get<OrderHistoryPage>(`${this.API_URL}/orders/history`, { params });

  }

  submitOrder(request: OrderSubmissionRequest): Observable<OrderSubmissionResponse> {
    return this.http.post<OrderSubmissionResponse>(`${this.API_URL}/orders`, request);
  }
}
