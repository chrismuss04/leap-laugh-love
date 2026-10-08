import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { TradeOpsService } from './trade-ops';

describe('TradeOpsService', () => {
  let service: TradeOpsService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    service = TestBed.inject(TradeOpsService);
    http = TestBed.inject(HttpTestingController);
  });
  afterEach(() => http.verify());

  // Verify only filled-in search fields are sent, trimmed.
  it('searches by the filled-in fields', () => {
    service.search({ orderId: '', clientEmail: ' alice@leap.com ', symbol: 'AAPL', from: '2026-10-01' }).subscribe();
    const request = http.expectOne(r => r.url === '/api/order/ops/orders');
    expect(request.request.params.keys().sort()).toEqual(['clientEmail', 'from', 'symbol']);
    expect(request.request.params.get('clientEmail')).toBe('alice@leap.com');
    request.flush([]);
  });

  // Verify a trade's timeline is loaded by order ID.
  it('loads a timeline', () => {
    service.timeline('order-1').subscribe();
    expect(http.expectOne('/api/order/ops/orders/order-1/timeline').request.method).toBe('GET');
  });

  // Verify the CSV is fetched as a file, so the auth interceptor can sign the request.
  it('downloads the timeline as CSV', () => {
    let file: Blob | undefined;
    service.timelineCsv('order-1').subscribe(blob => file = blob);
    const request = http.expectOne('/api/order/ops/orders/order-1/timeline.csv');
    expect(request.request.responseType).toBe('blob');
    request.flush(new Blob(['order_id,time_utc,step,description\r\n']));
    expect(file).toBeDefined();
  });
});
