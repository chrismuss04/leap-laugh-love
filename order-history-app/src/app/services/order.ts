import { Injectable } from '@angular/core';
import { HttpClient, HttpHeaders } from '@angular/common/http';
import { Observable } from 'rxjs';
import { AuthService } from './auth';

export interface ExecutionItem {
  executionId: string;
  quantity: number;
  price: number;
  executedAt: string;
}

export interface OrderHistoryItem {
  orderId: string;
  symbol: string;
  side: string;
  quantity: number;
  status: string;
  submittedAt: string;
  filledAt: string | null;
  executions: ExecutionItem[];
}

export interface OrderHistoryPage {
  content: OrderHistoryItem[];
  number: number;
  size: number;
  totalElements: number;
  totalPages: number;
  first: boolean;
  last: boolean;
}

@Injectable({
  providedIn: 'root'
})
export class OrderService {
  private readonly API_URL = 'http://localhost:8082/api/trading';

  constructor(
    private http: HttpClient,
    private authService: AuthService
  ) {}

  getOrderHistory(clientId: string, page: number = 0, size: number = 20): Observable<OrderHistoryPage> {
    const token = this.authService.getAccessToken();
    const headers = new HttpHeaders({
      'Authorization': `Bearer ${token}`
    });

    return this.http.get<OrderHistoryPage>(
      `${this.API_URL}/orders/history`,
      {
        headers,
        params: {
          clientId,
          page: page.toString(),
          size: size.toString()
        }
      }
    );
  }
}
