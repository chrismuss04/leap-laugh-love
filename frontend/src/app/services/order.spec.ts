import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { OrderService } from './order';

describe('OrderService', () => {
  let service: OrderService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    service = TestBed.inject(OrderService);
    http = TestBed.inject(HttpTestingController);
  });
  afterEach(() => http.verify());

  // Verify unfiltered history uses default pagination without sending null date filters.
  it('loads default history', () => {
    service.getOrderHistory().subscribe();
    const request = http.expectOne(r => r.url === '/api/order/orders/history');
    expect(request.request.method).toBe('GET');
    expect(request.request.params.keys().sort()).toEqual(['page', 'size']);
    expect(request.request.params.get('page')).toBe('0');
    expect(request.request.params.get('size')).toBe('20');
    request.flush({ content: [] });
  });

  // Verify pagination and date filters are passed to the order API together.
  it('sends history filters', () => {
    service.getOrderHistory(2, 10, 2024, 2, 29).subscribe();
    const request = http.expectOne(r => r.url === '/api/order/orders/history');
    for (const [key, value] of Object.entries({ page: '2', size: '10', year: '2024', month: '2', day: '29' })) {
      expect(request.request.params.get(key)).toBe(value);
    }
    request.flush({ content: [] });
  });

  // Verify a market order sends its exact details without supplying an execution price.
  it('submits a market order', () => {
    const order = { accountId: 'account-1', symbol: 'AAPL', side: 'BUY' as const, quantity: 2 };
    service.submitOrder(order).subscribe();
    const request = http.expectOne('/api/order/orders');
    expect(request.request.method).toBe('POST');
    expect(request.request.body).toEqual(order);
    expect(request.request.body.price).toBeUndefined();
    request.flush({ orderId: 'order-1', status: 'ACCEPTED' });
  });

  // Verify submission failures reach the caller without automatically placing the order again.
  it('surfaces submission failure', () => {
    let status: number | undefined;
    service.submitOrder({ accountId: 'a', symbol: 'AAPL', side: 'SELL', quantity: 1 })
      .subscribe({ next: () => fail('Expected an error'), error: error => status = error.status });
    http.expectOne('/api/order/orders').flush({}, { status: 503, statusText: 'Unavailable' });
    expect(status).toBe(503);
    http.expectNone('/api/order/orders');
  });
});
