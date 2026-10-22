import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';

export type PortfolioRange = '1D' | '1W' | '1M' | '3M' | '1Y' | 'ALL';

export const PORTFOLIO_RANGES: { range: PortfolioRange; label: string }[] = [
  { range: '1D', label: 'Today' },
  { range: '1W', label: 'Past week' },
  { range: '1M', label: 'Past month' },
  { range: '3M', label: 'Past 3 months' },
  { range: '1Y', label: 'Past year' },
  { range: 'ALL', label: 'All time' }
];

export interface PortfolioPoint {
  timestamp: string;
  value: number;
  /** Money deposited less withdrawn since the first point: part of the value, but not a gain. */
  contributions: number;
}

export interface PortfolioHistory {
  range: PortfolioRange;
  intervalSeconds: number;
  points: PortfolioPoint[];
  startValue: number;
  endValue: number;
  /** Money deposited less withdrawn over the range, which change leaves out. */
  netContributions: number;
  change: number;
  changePercent: number | null;
}

@Injectable({
  providedIn: 'root'
})
export class PortfolioService {
  // Same-origin path, proxied to account-app - see proxy.conf.js.
  private readonly API_URL = '/api/account/portfolio';

  constructor(private http: HttpClient) {}

  /** The value of every account combined over a range, or of one account when given. */
  getHistory(range: PortfolioRange, accountId?: string | null): Observable<PortfolioHistory> {
    const params: Record<string, string> = accountId ? { range, accountId } : { range };
    return this.http.get<PortfolioHistory>(`${this.API_URL}/history`, { params });
  }
}
