import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';

export interface AccountBalance {
  accountId: string;
  accountNumber: string;
  currency: string;
  balance: number;
}

export interface BalanceResponse {
  accounts: AccountBalance[];
  totalsByCurrency: Record<string, number>;
}

export interface CashMovementRequest {
  amount: number;
  description?: string;
}

/** One cash ledger entry, e.g. a deposit, and the account's balance after it. */
export interface CashTransactionResponse {
  cashLedgerId: string;
  accountId: string;
  entryType: string;
  amount: number;
  currency: string;
  balanceAfter: number;
  createdAt: string;
  description: string | null;
}

@Injectable({
  providedIn: 'root'
})
export class BalanceService {
  // Same-origin path, proxied to account-app - see proxy.conf.js.
  private readonly API_URL = '/api/account';

  constructor(private http: HttpClient) {}

  getBalance(): Observable<BalanceResponse> {
    return this.http.get<BalanceResponse>(`${this.API_URL}/balance`);
  }

  deposit(accountId: string, request: CashMovementRequest): Observable<CashTransactionResponse> {
    return this.http.post<CashTransactionResponse>(`${this.API_URL}/balance/accounts/${accountId}/deposit`, request);
  }
}
