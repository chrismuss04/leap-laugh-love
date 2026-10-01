import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { AccountSummary } from './holdings';

/** Opens another brokerage account under the signed-in client's profile. */
export interface OpenAccountRequest {
  baseCurrency: string;
}

/** Moves cash between two of the signed-in client's own accounts. */
export interface CashTransferRequest {
  fromAccountId: string;
  toAccountId: string;
  amount: number;
  description?: string;
}

export interface CashTransferResponse {
  transferId: string;
  fromAccountId: string;
  toAccountId: string;
  amount: number;
  currency: string;
  fromBalanceAfter: number;
  toBalanceAfter: number;
  createdAt: string;
}

@Injectable({
  providedIn: 'root'
})
export class AccountsService {
  // Same-origin path, proxied to account-app - see proxy.conf.js.
  private readonly API_URL = '/api/account';

  constructor(private http: HttpClient) {}

  getAccounts(): Observable<AccountSummary[]> {
    return this.http.get<AccountSummary[]>(`${this.API_URL}/accounts`);
  }

  openAccount(request: OpenAccountRequest): Observable<AccountSummary> {
    return this.http.post<AccountSummary>(`${this.API_URL}/accounts`, request);
  }

  transferCash(request: CashTransferRequest): Observable<CashTransferResponse> {
    return this.http.post<CashTransferResponse>(`${this.API_URL}/balance/transfers`, request);
  }

  /** Saves an account's price protection in percent; null turns it off. */
  updateTradeSettings(accountId: string, maxSlippagePercent: number | null): Observable<AccountSummary> {
    return this.http.put<AccountSummary>(`${this.API_URL}/accounts/${encodeURIComponent(accountId)}/trade-settings`,
      { maxSlippagePercent });
  }
}
