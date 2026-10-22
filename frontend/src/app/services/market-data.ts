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

/** The current bid/ask: what a sell or a buy would fill at right now. */
export interface Quote {
  symbol: string;
  bidPrice: number;
  askPrice: number;
  lastPrice: number;
  quoteTimestamp: string;
}

/** A stretch of 5-minute closes, oldest first, with when each candle began (epoch ms). */
export interface IntradayCloses {
  closes: number[];
  starts: number[];
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

  constructor(private readonly http: HttpClient) {}

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

  getQuote(symbol: string): Observable<Quote> {
    return this.http.get<Quote>(`${this.API_URL}/quotes/${encodeURIComponent(symbol)}`);
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
   * The price at the previous trading day's regular close - the baseline a "today" change is
   * measured from. Market data works out which close that is from the exchange calendar, the same
   * one account-app counts today's trades and history from.
   */
  getPreviousClose(symbol: string): Observable<number | null> {
    return this.http.get<{ close: number }>(`${this.API_URL}/prices/${encodeURIComponent(symbol)}/previous-close`).pipe(
      map(response => response?.close ?? null),
      catchError(() => of(null))
    );
  }

  /**
   * The latest day of 5-minute closes (288 of them), for sparklines and the order ticket's chart.
   * Empty on failure - a sparkline is decoration.
   *
   * Candles are only recorded while the market trades, so this reaches back up to a week for them
   * and takes the newest: while the market is closed, or paused for the night or a weekend, it is
   * the last stretch that traded rather than nothing at all. Each close comes with when its candle
   * began, so a chart can tell which session it traded in.
   */
  getIntradayCloses(symbol: string): Observable<IntradayCloses> {
    const to = new Date();
    const from = new Date(to.getTime() - 7 * 86_400_000);
    return this.getHistory(symbol, from, to, 300, 288).pipe(
      map(candles => ({
        closes: candles.map(candle => candle.close),
        starts: candles.map(candle => Date.parse(candle.bucketStart))
      })),
      catchError(() => of({ closes: [], starts: [] }))
    );
  }
}
