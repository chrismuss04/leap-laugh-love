import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { forkJoin, map, Observable, of, switchMap } from 'rxjs';

export interface PositionItem {
  instrumentId: string;
  symbol: string;
  instrumentName: string;
  assetClass: string;
  quantity: number;
  averageCost: number;
  /**
   * Shares held when the trading day began (midnight UTC, when the previous close was struck).
   * Without it, every share is taken to have been held since the open.
   */
  quantityAtOpen?: number;
  /** Cash the position returned today: sale proceeds less purchase costs, so negative after a buy. */
  todayCashFlow?: number;
}

export interface AccountHoldings {
  accountId: string;
  accountNumber: string;
  baseCurrency: string;
  positions: PositionItem[];
  /** Positions sold down to nothing today, whose gain or loss still counts toward today's. */
  closedToday?: PositionItem[];
}

export interface ClientHoldings {
  accounts: AccountHoldings[];
}

export interface AccountSummary {
  accountId: string;
  accountNumber: string;
  status: string;
  baseCurrency: string;
  tradingEnabled: boolean;
  /** Saved price protection in percent; orders without their own use it. Null when off. */
  maxSlippagePercent: number | null;
  createdAt: string;
  inactiveSince: string | null;
  /** The account's place among the client's accounts (1, 2, ...); null if it was never numbered. */
  accountSeq: number | null;
}

@Injectable({
  providedIn: 'root'
})
export class HoldingsService {
  // Same-origin path, proxied to account-app - see proxy.conf.js.
  private readonly API_URL = '/api/account';

  constructor(private http: HttpClient) {}

  getHoldings(): Observable<ClientHoldings> {
    return this.http.get<AccountSummary[]>(`${this.API_URL}/accounts`).pipe(
      switchMap((accounts) => {
        if (!accounts || accounts.length === 0) {
          return of({ accounts: [] });
        }
        return forkJoin(
          accounts.map((acc) =>
            this.http.get<AccountHoldings>(`${this.API_URL}/accounts/${acc.accountId}/positions`)
          )
        ).pipe(map((accountHoldings) => ({ accounts: accountHoldings })));
      })
    );
  }

  getAccounts(): Observable<AccountSummary[]> {
    return this.http.get<AccountSummary[]>(`${this.API_URL}/accounts`);
  }

  getPositionsForAccount(accountId: string): Observable<AccountHoldings> {
    return this.http.get<AccountHoldings>(`${this.API_URL}/accounts/${accountId}/positions`);
  }
}
