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

/** Empty fields aren't sent; from and to are inclusive UTC days (yyyy-MM-dd). */
export interface TradeSearch {
  orderId?: string;
  clientEmail?: string;
  accountNumber?: string;
  symbol?: string;
  from?: string;
  to?: string;
}

/** Trade reconstruction for Trading Operations; order-app refuses every other role. */
@Injectable({
  providedIn: 'root'
})
export class TradeOpsService {
  // Same-origin path, proxied to order-app - see proxy.conf.js.
  private readonly API_URL = '/api/order/ops/orders';

  constructor(private http: HttpClient) {}

  search(criteria: TradeSearch): Observable<TradeSummary[]> {
    const params = Object.fromEntries(
      Object.entries(criteria).map(([key, value]) => [key, value?.trim() ?? '']).filter(([, value]) => value));
    return this.http.get<TradeSummary[]>(this.API_URL, { params });
  }

  timeline(orderId: string): Observable<TradeTimeline> {
    return this.http.get<TradeTimeline>(`${this.API_URL}/${orderId}/timeline`);
  }

  timelineCsv(orderId: string): Observable<Blob> {
    return this.http.get(`${this.API_URL}/${orderId}/timeline.csv`, { responseType: 'blob' });
  }
}
