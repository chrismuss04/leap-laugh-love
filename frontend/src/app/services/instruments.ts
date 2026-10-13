import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';

export type InstrumentPeriod = 'today' | 'week' | 'month';

export interface InstrumentOverviewRow {
  symbol: string;
  name: string;
  /** Null for simulated instruments without trading metadata. */
  assetClass: string | null;
  currency: string | null;
  price: number;
  asOf: string;
  /** Null when no valid completed daily baseline is available. */
  baselinePrice: number | null;
  change: number | null;
  changePercent: number | null;
}

export interface InstrumentOverview {
  period: InstrumentPeriod;
  periodStart: string;
  asOf: string;
  instruments: InstrumentOverviewRow[];
}

@Injectable({ providedIn: 'root' })
export class InstrumentsService {
  private readonly http = inject(HttpClient);

  /** Fetch afresh for period changes and retries; preserve errors for the page. */
  getOverview(period: InstrumentPeriod = 'today'): Observable<InstrumentOverview> {
    return this.http.get<InstrumentOverview>('/api/marketdata/instruments', {
      params: { period }
    });
  }
}
