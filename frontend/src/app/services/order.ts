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
  /** MARKET for orders placed before order types existed. */
  orderType?: OrderType;
  limitPrice?: number | null;
  stopPrice?: number | null;
  /**
   * When the order filled, was rejected, cancelled or expired - or was submitted, while it's still
   * open. History is listed newest first by this.
   */
  completedAt?: string;
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

/**
 * MARKET fills at once. LIMIT fills only at its limit price or better. STOP becomes a market order
 * once the price reaches its stop price, and STOP_LIMIT becomes a limit order then.
 */
export type OrderType = 'MARKET' | 'LIMIT' | 'STOP' | 'STOP_LIMIT';

/** How long an order that hasn't filled keeps working: today, or until cancelled (up to 90 days). */
export type TimeInForce = 'DAY' | 'GTC';

export type OrderStatus = 'SUBMITTED' | 'OPEN' | 'ACCEPTED' | 'REJECTED' | 'FILLED' | 'CANCELLED' | 'EXPIRED';

/**
 * An order. A market order (the default) is filled at the live ask (buy) or bid (sell); with price
 * protection - maxSlippagePercent, or else the account's saved one - it's rejected if that fill is
 * further than the protection from quotedPrice, the price the client reviewed. Other types wait
 * OPEN until the market reaches their price; price protection doesn't apply to them.
 */
export interface OrderSubmissionRequest {
  accountId: string;
  symbol: string;
  side: OrderSide;
  quantity: number;
  quotedPrice?: number;
  maxSlippagePercent?: number;
  orderType?: OrderType;
  limitPrice?: number;
  stopPrice?: number;
  timeInForce?: TimeInForce;
}

export interface OrderSubmissionResponse {
  orderId: string;
  accountId: string;
  accountNumber: string;
  symbol: string;
  side: OrderSide;
  quantity: number;
  status: OrderStatus;
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
  orderType?: OrderType;
  limitPrice?: number | null;
  stopPrice?: number | null;
  timeInForce?: TimeInForce | null;
  expiresAt?: string | null;
  /** Cash an OPEN buy holds back from buying power. */
  reservedCash?: number | null;
}

/** An order waiting for the market to reach its price. */
export interface OpenOrder {
  orderId: string;
  accountId: string;
  symbol: string;
  side: OrderSide;
  quantity: number;
  status: OrderStatus;
  orderType: Exclude<OrderType, 'MARKET'>;
  limitPrice: number | null;
  stopPrice: number | null;
  timeInForce: TimeInForce;
  submittedAt: string;
  expiresAt: string | null;
  /** When a stop-limit's stop was reached; it now works as a limit order. */
  triggeredAt: string | null;
  /** Cash a buy holds back from buying power; null for a sell. */
  reservedCash: number | null;
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

  /** The client's open orders across all accounts, newest first. */
  getOpenOrders(): Observable<OpenOrder[]> {
    return this.http.get<OpenOrder[]>(`${this.API_URL}/orders/open`);
  }

  /** Cancels an open order; fails with 409 if it already filled. */
  cancelOrder(orderId: string): Observable<OpenOrder> {
    return this.http.post<OpenOrder>(`${this.API_URL}/orders/${encodeURIComponent(orderId)}/cancel`, {});
  }
}
