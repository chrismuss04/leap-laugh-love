import { Injectable, inject, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';

/** The value of the month's fills, buys and sells. */
export interface RecapMoneyMoved {
  total: number;
  /** The value of the buy fills. */
  bought: number;
  /** The value of the sell fills. */
  sold: number;
  filledOrders: number;
  /** How many of the client's accounts traded. */
  accounts: number;
}

/** One of the client's most-traded symbols. */
export interface RecapTicker {
  symbol: string;
  tradedValue: number;
  filledOrders: number;
  /** Daily closes through the month, oldest first; empty when the symbol has no candles. */
  closes: number[];
}

/** returnPct is the gain over the most money the position had in it at once. */
export interface RecapSymbolReturn {
  symbol: string;
  returnPct: number;
  pnl: number;
}

export interface RecapTopGainer extends RecapSymbolReturn {
  closes: number[];
}

export interface RecapTradingReturn {
  /** null when nothing the client held or traded could be priced. */
  returnPct: number | null;
  pnl: number;
  /** The gain so far at the end of each day of the month. */
  dailyPnl: number[];
}

export interface RecapBenchmark {
  symbol: string;
  name: string;
  returnPct: number;
  /** The client's trading return minus the index's, in percentage points; null without one. */
  differencePts: number | null;
}

export interface RecapTopSector {
  sector: string;
  /** The short name the card has room for, e.g. "Tech". */
  label: string;
  tradedValue: number;
}

/**
 * One client's recap of one UTC month, as reporting-app's RecapPayload. quiet means no fills in the
 * month: the trading figures are empty, but positions held from before can still carry a return.
 */
export interface RecapPayload {
  /** yyyy-MM */
  month: string;
  quiet: boolean;
  moneyMoved: RecapMoneyMoved;
  topTickers: RecapTicker[];
  topGainer: RecapTopGainer | null;
  topGainers: RecapSymbolReturn[];
  biggestLosers: RecapSymbolReturn[];
  tradingReturn: RecapTradingReturn;
  benchmark: RecapBenchmark | null;
  instrumentsTraded: number;
  topSector: RecapTopSector | null;
  /** The smallest top slice of clients by trading return; null outside the top half or unranked. */
  topPercent: number | null;
}

/**
 * ranked is false when the month's job hasn't run yet and the recap was worked out for this
 * request alone, so it has no ranking.
 */
export interface RecapResponse {
  month: string;
  generatedAt: string;
  ranked: boolean;
  recap: RecapPayload;
}

/** Last month in UTC, as yyyy-MM: the month /latest recaps. */
export function lastRecapMonth(now = new Date()): string {
  const month = new Date(Date.UTC(now.getUTCFullYear(), now.getUTCMonth() - 1, 1));
  return `${month.getUTCFullYear()}-${String(month.getUTCMonth() + 1).padStart(2, '0')}`;
}

// The last month whose recap this browser has opened, so the nav can flag a new one.
const SEEN_KEY = 'recap_seen_month';

function readSeen(): string | null {
  try {
    return localStorage.getItem(SEEN_KEY);
  } catch {
    return null;
  }
}

/** Monthly recaps, served by reporting-app to the signed-in client. */
@Injectable({
  providedIn: 'root'
})
export class RecapService {
  // Same-origin path, proxied to reporting-app - see proxy.conf.js.
  private readonly API_URL = '/api/reporting/recaps';
  private readonly http = inject(HttpClient);

  /** True until this browser opens last month's recap; drives the nav's "new" marker. */
  readonly unseen = signal(readSeen() !== lastRecapMonth());

  /** The signed-in client's recap of last month. 503 RECAP_UNAVAILABLE when prices are down. */
  latest(): Observable<RecapResponse> {
    return this.http.get<RecapResponse>(`${this.API_URL}/latest`);
  }

  /** Records that the month's recap has been opened. */
  markSeen(month: string): void {
    try {
      localStorage.setItem(SEEN_KEY, month);
    } catch {
      // Storage blocked: the marker just stays until it isn't.
    }
    this.unseen.set(month !== lastRecapMonth());
  }
}
