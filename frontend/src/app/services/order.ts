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
  trailAmount?: number | null;
  trailPercent?: number | null;
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
 * once the price reaches its stop price, and STOP_LIMIT becomes a limit order then. TRAILING_STOP is
 * a stop that follows the price in your favour, a set amount or percent behind the best price seen.
 */
export type OrderType = 'MARKET' | 'LIMIT' | 'STOP' | 'STOP_LIMIT' | 'TRAILING_STOP';

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
  /** For TRAILING_STOP: how many dollars behind the best price the stop trails. */
  trailAmount?: number;
  /** For TRAILING_STOP: how many percent behind the best price the stop trails. Give this or trailAmount. */
  trailPercent?: number;
  /**
   * For a BUY: attach a bracket. Once the buy fills, order-app opens a sell limit at the take-profit
   * price and a sell stop at the stop-loss price; when one executes the other is cancelled. Give both.
   */
  bracketTakeProfitPrice?: number;
  bracketStopLossPrice?: number;
}

/** A change to an open order. Only the fields given change; each must apply to the order's type. */
export interface OrderModification {
  quantity?: number;
  limitPrice?: number;
  stopPrice?: number;
  trailAmount?: number;
  trailPercent?: number;
  timeInForce?: TimeInForce;
}

/** An order ending without the client doing anything, pushed on the order stream. */
export interface OrderUpdate {
  orderId: string;
  symbol: string;
  side: OrderSide;
  quantity: number;
  orderType: OrderType;
  /** FILLED, ACCEPTED (executed, still being confirmed), REJECTED or EXPIRED. */
  status: OrderStatus;
  fillPrice: number | null;
  reason: string | null;
  at: string;
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
  trailAmount?: number | null;
  trailPercent?: number | null;
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
  /** For a trailing stop: the dollars or percent its stop trails the best price by. */
  trailAmount?: number | null;
  trailPercent?: number | null;
  /** For a trailing stop: the best price seen, the high for a sell and the low for a buy. */
  trailPeak?: number | null;
  /** For a bracket's exit order: the buy whose fill opened it. */
  parentOrderId?: string | null;
  /** For a bracket's exit order: the group its two legs share, where one executing cancels the other. */
  ocoGroupId?: string | null;
  /** For a buy with a bracket: the exit prices it will open when it fills. */
  bracketTakeProfitPrice?: number | null;
  bracketStopLossPrice?: number | null;
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
    if (year !== null) { params['year'] = year.toString(); }
    if (month !== null) { params['month'] = month.toString(); }
    if (day !== null) { params['day'] = day.toString(); }

    return this.http.get<OrderHistoryPage>(`${this.API_URL}/orders/history`, { params });

  }

  submitOrder(request: OrderSubmissionRequest): Observable<OrderSubmissionResponse> {
    return this.http.post<OrderSubmissionResponse>(`${this.API_URL}/orders`, request);
  }

  /** The client's open orders across all accounts, newest first. */
  getOpenOrders(): Observable<OpenOrder[]> {
    return this.http.get<OpenOrder[]>(`${this.API_URL}/orders/open`);
  }

  /** Changes an open order in place; fails with 409 if it is no longer open. */
  modifyOrder(orderId: string, changes: OrderModification): Observable<OpenOrder> {
    return this.http.patch<OpenOrder>(`${this.API_URL}/orders/${encodeURIComponent(orderId)}`, changes);
  }

  /** Cancels an open order; fails with 409 if it already filled. */
  cancelOrder(orderId: string): Observable<OpenOrder> {
    return this.http.post<OpenOrder>(`${this.API_URL}/orders/${encodeURIComponent(orderId)}/cancel`, {});
  }
}
