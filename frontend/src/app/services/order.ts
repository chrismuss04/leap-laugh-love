import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';

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

  constructor(private http: HttpClient) {}

  getOrderHistory(page: number = 0, size: number = 20): Observable<OrderHistoryPage> {
    return this.http.get<OrderHistoryPage>(
      `${this.API_URL}/orders/history`,
      {
        params: {
          page: page.toString(),
          size: size.toString()
        }
      }
    );
  }
}
