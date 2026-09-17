import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';

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

@Injectable({
  providedIn: 'root'
})
export class HoldingsService {
  // Same-origin path, proxied to trading-app - see proxy.conf.js.
  private readonly API_URL = '/api/trading';

  constructor(private http: HttpClient) {}

  getHoldings(): Observable<ClientHoldings> {
    return this.http.get<ClientHoldings>(`${this.API_URL}/positions`);
  }
}
