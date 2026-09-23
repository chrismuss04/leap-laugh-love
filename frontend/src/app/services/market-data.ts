import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable, of } from 'rxjs';
import { catchError, map, shareReplay } from 'rxjs/operators';

export interface LatestPrice {
  symbol: string;
  /** Company or index name, e.g. "Apple Inc."; null if market data has none. */
  name: string | null;
  price: number;
  asOf: string;
}

export interface Candle {
  bucketStart: string;
  bucketSeconds: number;
  open: number;
  high: number;
  low: number;
  close: number;
}

interface CandlePage {
  content: Candle[];
  last: boolean;
}

/** Candle widths the market data service serves, in seconds. */
export type CandleInterval = 60 | 300 | 3600 | 86400;

@Injectable({
  providedIn: 'root'
})
export class MarketDataService {
  // Same-origin path, proxied to market-data-app - see proxy.conf.js.
  private readonly API_URL = '/api/marketdata';

  private allPrices$?: Observable<LatestPrice[]>;

  constructor(private http: HttpClient) {}

  /** Latest price of every simulated instrument. Cached: it backs symbol search, not live figures. */
  getAllLatestPrices(): Observable<LatestPrice[]> {
    this.allPrices$ ??= this.http.get<LatestPrice[]>(`${this.API_URL}/prices`).pipe(
      catchError(() => {
        this.allPrices$ = undefined;
        return of([]);
      }),
      shareReplay(1)
    );
    return this.allPrices$;
  }

  getLatestPrice(symbol: string): Observable<LatestPrice> {
    return this.http.get<LatestPrice>(`${this.API_URL}/prices/${encodeURIComponent(symbol)}`);
  }

  /** Candles in a range, oldest first (the API pages newest first). */
  getHistory(symbol: string, from: Date, to: Date, interval: CandleInterval, size = 1000): Observable<Candle[]> {
    return this.http.get<CandlePage>(`${this.API_URL}/prices/${encodeURIComponent(symbol)}/history`, {
      params: {
        from: from.toISOString(),
        to: to.toISOString(),
        interval: interval.toString(),
        size: size.toString()
      }
    }).pipe(map(page => [...page.content].reverse()));
  }

  /**
   * The close of the last full UTC day - the baseline a "today" change is measured from. The
   * simulated market trades around the clock, so a day is a UTC calendar day.
   */
  getPreviousClose(symbol: string): Observable<number | null> {
    const startOfToday = new Date();
    startOfToday.setUTCHours(0, 0, 0, 0);
    const to = new Date(startOfToday.getTime() - 1000);
    const from = new Date(startOfToday.getTime() - 7 * 86_400_000);
    return this.http.get<CandlePage>(`${this.API_URL}/prices/${encodeURIComponent(symbol)}/history`, {
      params: { from: from.toISOString(), to: to.toISOString(), interval: '86400', size: '1' }
    }).pipe(
      map(page => page.content[0]?.close ?? null),
      catchError(() => of(null))
    );
  }

  /** Last 24 hours of 5-minute closes, for sparklines. Empty on failure - a sparkline is decoration. */
  getIntradayCloses(symbol: string): Observable<number[]> {
    const to = new Date();
    const from = new Date(to.getTime() - 86_400_000);
    return this.getHistory(symbol, from, to, 300, 300).pipe(
      map(candles => candles.map(candle => candle.close)),
      catchError(() => of([]))
    );
  }
}
