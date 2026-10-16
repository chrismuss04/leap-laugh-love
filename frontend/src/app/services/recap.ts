import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';

// Monthly recap: one client's month, built by reporting-app. Months are yyyy-MM, UTC. Money is
// in the account currency, rounded to cents; percentages are to two places.

/** How much the client traded: every fill's value, buys and sells. */
export interface RecapMoneyMoved {
  total: number;
  perMarketDay: number;
  /** The month's weekdays. */
  marketDays: number;
  filledOrders: number;
  /** How many of the client's accounts traded. */
  accounts: number;
}

export interface RecapTickerActivity {
  symbol: string;
  tradedValue: number;
  filledOrders: number;
  /** Daily closes through the month, oldest first; empty when the symbol has no prices. */
  closes: number[];
}

export interface RecapSymbolReturn {
  symbol: string;
  returnPct: number;
  /** Negative for a loss. */
  pnl: number;
}

export interface RecapTopGainer extends RecapSymbolReturn {
  closes: number[];
}

export interface RecapTradingReturn {
  /** Null when nothing the client held or traded could be priced. */
  returnPct: number | null;
  pnl: number;
  /** The gain so far at the end of each day of the month. */
  dailyPnl: number[];
}

export interface RecapBenchmark {
  symbol: string;
  name: string;
  returnPct: number;
  /** The client's trading return minus the index's, in points; null with no trading return. */
  differencePts: number | null;
}

export interface RecapTopSector {
  sector: string;
  /** The short name the card has room for. */
  label: string;
  tradedValue: number;
}

/** Everything the recap cards show. Nullable parts are missing when the month gives nothing to show. */
export interface RecapPayload {
  month: string;
  /** No fills in the month. Positions held from before can still give a return and a ranking. */
  quiet: boolean;
  moneyMoved: RecapMoneyMoved;
  /** Up to five, most traded first. */
  topTickers: RecapTickerActivity[];
  topGainer: RecapTopGainer | null;
  topGainers: RecapSymbolReturn[];
  biggestLosers: RecapSymbolReturn[];
  tradingReturn: RecapTradingReturn;
  benchmark: RecapBenchmark | null;
  instrumentsTraded: number;
  topSector: RecapTopSector | null;
  /** The smallest top slice of the month's clients the client is in; null when not in the top half or unranked. */
  topPercent: number | null;
}

export interface RecapResponse {
  month: string;
  generatedAt: string;
  /** False when the month's job hadn't run yet and the recap was worked out for this request, without a ranking. */
  ranked: boolean;
  recap: RecapPayload;
}

@Injectable({ providedIn: 'root' })
export class RecapApi {
  private readonly http = inject(HttpClient);

  /** The signed-in client's recap of last month. 503 while prices are unavailable. */
  latest(): Observable<RecapResponse> {
    // Same-origin path, proxied to reporting-app - see proxy.conf.js.
    return this.http.get<RecapResponse>('/api/reporting/recaps/latest');
  }
}
