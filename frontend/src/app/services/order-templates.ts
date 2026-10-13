import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { OrderSide, OrderType, TimeInForce } from './order';

/**
 * A saved ticket setup. Prices are percent offsets from the price the side fills at (the ask for a
 * buy, the bid for a sell), so the template fits whatever the market is on the day it is applied.
 * A bracket's percents are measured from the entry price.
 */
export interface OrderTemplate {
  templateId: string;
  name: string;
  side: OrderSide;
  orderType: OrderType;
  timeInForce: TimeInForce;
  limitOffsetPercent: number | null;
  stopOffsetPercent: number | null;
  trailPercent: number | null;
  takeProfitPercent: number | null;
  stopLossPercent: number | null;
  createdAt: string;
}

/** What is sent to save a template. */
export type OrderTemplateRequest = Omit<OrderTemplate, 'templateId' | 'createdAt'>;

/** How many templates a client may keep; order-app refuses more. */
export const MAX_TEMPLATES = 20;

@Injectable({
  providedIn: 'root'
})
export class OrderTemplateService {
  // Same-origin path, proxied to order-app - see proxy.conf.js.
  private readonly API_URL = '/api/order/templates';

  constructor(private http: HttpClient) {}

  /** The client's templates, newest first. */
  list(): Observable<OrderTemplate[]> {
    return this.http.get<OrderTemplate[]>(this.API_URL);
  }

  /** Saves a template; fails with 409 if the name is taken or the limit is reached. */
  create(template: OrderTemplateRequest): Observable<OrderTemplate> {
    return this.http.post<OrderTemplate>(this.API_URL, template);
  }

  delete(templateId: string): Observable<void> {
    return this.http.delete<void>(`${this.API_URL}/${encodeURIComponent(templateId)}`);
  }
}
