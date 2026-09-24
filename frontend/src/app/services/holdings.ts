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
}

export interface AccountHoldings {
  accountId: string;
  accountNumber: string;
  baseCurrency: string;
  positions: PositionItem[];
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
  createdAt: string;
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
