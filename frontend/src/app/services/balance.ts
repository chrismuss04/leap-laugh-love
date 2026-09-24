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
}
