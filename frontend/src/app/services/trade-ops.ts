import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';

export interface TradeSummary {
  orderId: string;
  submittedAt: string;
  clientEmail: string;
  accountNumber: string;
  symbol: string;
  side: 'BUY' | 'SELL';
  quantity: number;
  status: string;
}

export interface TimelineStep {
  at: string;
  type: string;
  description: string;
}

export interface TradeTimeline {
  trade: TradeSummary;
  quotedPrice: number | null;
  maxSlippagePercent: number | null;
  steps: TimelineStep[];
}

/** How many orders a search matches, by outcome. awaitingExecution is SUBMITTED, ACCEPTED or OPEN. */
export interface OrderCounts {
  submitted: number;
  filled: number;
  rejected: number;
  awaitingExecution: number;
}

/** Rejected orders over a period for one reason; reason is null for those recorded without one. */
export interface RejectionCount {
  reason: string | null;
  count: number;
  lastRejectedAt: string;
}

export interface OrderRecord {
  summary: TradeSummary;
  orderType: string;
  acceptedAt: string | null;
  rejectedAt: string | null;
  filledAt: string | null;
  rejectionReason: string | null;
  quotedPrice: number | null;
  maxSlippagePercent: number | null;
}

export interface ExecutionRecord {
  executionId: string;
  status: 'FILLED' | 'REJECTED';
  fillQuantity: number | null;
  fillPrice: number | null;
  executedAt: string;
  reason: string | null;
}

export interface CashEntryRecord {
  entryType: string;
  amount: number;
  currency: string;
  createdAt: string;
}

export interface PositionMovementRecord {
  movementType: string;
  quantityDelta: number;
  costDelta: number;
  createdAt: string;
}

/** One order's audit records as stored, each list oldest first. */
export interface TradeRecords {
  order: OrderRecord;
  executions: ExecutionRecord[];
  cashEntries: CashEntryRecord[];
  positionMovements: PositionMovementRecord[];
}

/**
 * Empty fields aren't sent. from and to are ISO date-times, from inclusive and to exclusive; order-app
 * needs them and a client id or email, and the rest only narrow the search.
 */
export interface TradeSearch {
  from: string;
  to: string;
  clientId?: string;
  clientEmail?: string;
  accountId?: string;
  accountNumber?: string;
  symbol?: string;
  side?: string;
  status?: string;
}

/** Trade reconstruction for Trading Operations; order-app refuses every other role. */
@Injectable({
  providedIn: 'root'
})
export class TradeOpsService {
  // Same-origin path, proxied to order-app - see proxy.conf.js.
  private readonly API_URL = '/api/order/ops/orders';
  private readonly REPORTS_URL = '/api/order/ops/reports';

  constructor(private http: HttpClient) {}

  search(criteria: TradeSearch): Observable<TradeSummary[]> {
    return this.http.get<TradeSummary[]>(this.API_URL, { params: searchParams(criteria) });
  }

  /** How many orders the same search matches, by outcome: all of them, not only those search returns. */
  counts(criteria: TradeSearch): Observable<OrderCounts> {
    return this.http.get<OrderCounts>(`${this.API_URL}/counts`, { params: searchParams(criteria) });
  }

  /** Orders submitted in the period and rejected, across every client, grouped by reason. */
  rejections(from: string, to: string): Observable<RejectionCount[]> {
    return this.http.get<RejectionCount[]>(`${this.REPORTS_URL}/rejections`, { params: { from, to } });
  }

  /** Orders across every client still waiting to execute well after submission, oldest first. */
  stuckOrders(): Observable<TradeSummary[]> {
    return this.http.get<TradeSummary[]>(`${this.REPORTS_URL}/stuck-orders`);
  }

  records(orderId: string): Observable<TradeRecords> {
    return this.http.get<TradeRecords>(`${this.API_URL}/${orderId}`);
  }

  timeline(orderId: string): Observable<TradeTimeline> {
    return this.http.get<TradeTimeline>(`${this.API_URL}/${orderId}/timeline`);
  }

  timelineCsv(orderId: string): Observable<Blob> {
    return this.http.get(`${this.API_URL}/${orderId}/timeline.csv`, { responseType: 'blob' });
  }
}

// Only the filled-in fields, trimmed.
function searchParams(criteria: TradeSearch): Record<string, string> {
  return Object.fromEntries(
    Object.entries(criteria).map(([key, value]) => [key, value?.trim() ?? '']).filter(([, value]) => value));
}
