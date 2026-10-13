import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';

export type ReportGranularity = 'DAILY' | 'WEEKLY';

export interface ReportInstrument {
  id: string;
  symbol: string;
}

/** Only filled orders count; values are quantity x fill price, volume is the quantity traded. */
export interface ActivityTotals {
  tradeCount: number;
  buyCount: number;
  sellCount: number;
  buyValue: number;
  sellValue: number;
  volume: number;
}

/** One UTC day, or one ISO week starting on its Monday. Only days or weeks with trades appear. */
export interface ActivityBucket {
  periodStart: string;
  totals: ActivityTotals;
}

/** No trading in the period is not an error: buckets is empty and the totals are zero. */
export interface ActivityReport {
  from: string;
  to: string;
  granularity: ReportGranularity;
  instrument: ReportInstrument | null;
  totals: ActivityTotals;
  buckets: ActivityBucket[];
}

/** from and to are UTC days (yyyy-MM-dd), both inclusive; no instrumentId means all instruments. */
export interface ActivityReportQuery {
  from: string;
  to: string;
  granularity: ReportGranularity;
  instrumentId?: string | null;
}

/**
 * One period's headline figures. Trading figures count filled orders only; a client is active when
 * any of their orders completed in the period, whatever its outcome.
 */
export interface InsightFigures {
  tradedValue: number;
  tradeCount: number;
  /** Null when nothing traded. */
  averageOrderValue: number | null;
  newRegistrations: number;
  activeClients: number;
}

export interface InstrumentActivity {
  symbol: string;
  tradeCount: number;
  tradedValue: number;
}

/** The previous period is as many days as the requested one, ending the day before it starts. */
export interface BusinessInsights {
  from: string;
  to: string;
  previousFrom: string;
  previousTo: string;
  current: InsightFigures;
  previous: InsightFigures;
  /** Most trades first; empty when nothing traded. */
  topInstruments: InstrumentActivity[];
}

/** Commercial analysts' activity reports; order-app refuses every other role. */
@Injectable({
  providedIn: 'root'
})
export class ReportsService {
  // Same-origin path, proxied to order-app - see proxy.conf.js.
  private readonly API_URL = '/api/order/reports';

  constructor(private http: HttpClient) {}

  getActivityReport(query: ActivityReportQuery): Observable<ActivityReport> {
    const params: Record<string, string> = {
      from: query.from,
      to: query.to,
      granularity: query.granularity
    };
    if (query.instrumentId) { params['instrumentId'] = query.instrumentId; }

    return this.http.get<ActivityReport>(`${this.API_URL}/activity`, { params });
  }

  /** from and to are days (yyyy-MM-dd), both inclusive. */
  getInsights(from: string, to: string): Observable<BusinessInsights> {
    return this.http.get<BusinessInsights>(`${this.API_URL}/insights`, { params: { from, to } });
  }

  getInstruments(): Observable<ReportInstrument[]> {
    return this.http.get<ReportInstrument[]>(`${this.API_URL}/instruments`);
  }
}
