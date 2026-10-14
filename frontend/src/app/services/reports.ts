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


// Analyst reports: the last 12 complete UTC months, served by reporting-app. Months are yyyy-MM.
// Every report has one entry per month (or sign-up month) in its window, oldest first.

/** The months a report covers, both inclusive. */
export interface ReportWindow {
  firstMonth: string;
  lastMonth: string;
}

/** A figure with its % change on last month and on the same month last year; null when there's nothing to compare. */
export interface ScorecardMetric {
  value: number | null;
  monthOverMonthPct: number | null;
  yearOverYearPct: number | null;
}

export interface ScorecardMonth {
  month: string;
  volume: ScorecardMetric;
  filledOrders: ScorecardMetric;
  /** value is null in a month with no filled orders. */
  averageTradeValue: ScorecardMetric;
  activeClients: ScorecardMetric;
  registrations: ScorecardMetric;
}

export interface ScorecardReport {
  window: ReportWindow;
  months: ScorecardMonth[];
}

/** activeClients = newClients + returningClients + reactivatedClients; lapsed clients aren't active. */
export interface ClientActivityMonth {
  month: string;
  activeClients: number;
  newClients: number;
  returningClients: number;
  reactivatedClients: number;
  lapsedClients: number;
}

export interface ClientActivityReport {
  window: ReportWindow;
  months: ClientActivityMonth[];
}

/** activePct is null for a cohort nobody signed up in. */
export interface RetentionCell {
  monthsSinceSignUp: number;
  month: string;
  activeClients: number;
  activePct: number | null;
}

export interface RetentionCohort {
  cohort: string;
  clients: number;
  months: RetentionCell[];
}

export interface RetentionReport {
  window: ReportWindow;
  cohorts: RetentionCohort[];
}

/** complete is false while some of the cohort are still inside their first 30 days. */
export interface ActivationCohort {
  cohort: string;
  registered: number;
  orderedWithin30Days: number;
  orderedWithin30DaysPct: number | null;
  orderedEver: number;
  orderedEverPct: number | null;
  medianDaysToFirstOrder: number | null;
  complete: boolean;
}

export interface ActivationReport {
  window: ReportWindow;
  cohorts: ActivationCohort[];
}

/** The top topPct percent of a month's trading clients, rounded up to whole clients. */
export interface ConcentrationTier {
  topPct: number;
  clients: number;
  volume: number;
  sharePct: number | null;
}

/** tiers is empty in a month nobody traded in. */
export interface ConcentrationMonth {
  month: string;
  tradingClients: number;
  volume: number;
  tiers: ConcentrationTier[];
}

export interface ConcentrationReport {
  window: ReportWindow;
  months: ConcentrationMonth[];
}

export type OrderSizeBandName = 'UNDER_1K' | 'FROM_1K' | 'FROM_5K' | 'FROM_10K' | 'FROM_25K';

/** from is inclusive, to exclusive; null at the open ends. */
export interface OrderSizeBand {
  band: OrderSizeBandName;
  from: number | null;
  to: number | null;
  orders: number;
  value: number;
}

/** bands lists every band, smallest first; meanValue and medianValue are null when nothing filled. */
export interface OrderSizeMonth {
  month: string;
  filledOrders: number;
  meanValue: number | null;
  medianValue: number | null;
  bands: OrderSizeBand[];
}

export interface OrderSizeReport {
  window: ReportWindow;
  months: OrderSizeMonth[];
}

/** Commercial analysts' reports; order-app and reporting-app refuse every other role. */
@Injectable({
  providedIn: 'root'
})
export class ReportsService {
  // Same-origin paths, proxied to order-app and reporting-app - see proxy.conf.js.
  private readonly API_URL = '/api/order/reports';
  private readonly ANALYST_URL = '/api/reporting/analyst';

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

  getScorecard(): Observable<ScorecardReport> {
    return this.http.get<ScorecardReport>(`${this.ANALYST_URL}/scorecard`);
  }

  getClientActivity(): Observable<ClientActivityReport> {
    return this.http.get<ClientActivityReport>(`${this.ANALYST_URL}/client-activity`);
  }

  getRetention(): Observable<RetentionReport> {
    return this.http.get<RetentionReport>(`${this.ANALYST_URL}/retention`);
  }

  getActivation(): Observable<ActivationReport> {
    return this.http.get<ActivationReport>(`${this.ANALYST_URL}/activation`);
  }

  getConcentration(): Observable<ConcentrationReport> {
    return this.http.get<ConcentrationReport>(`${this.ANALYST_URL}/concentration`);
  }

  getOrderSizes(): Observable<OrderSizeReport> {
    return this.http.get<OrderSizeReport>(`${this.ANALYST_URL}/order-sizes`);
  }
}
