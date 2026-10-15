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
    service.search({
      from: '2026-10-01T00:00:00.000Z', to: '2026-10-02T00:00:00.000Z', clientId: '', clientEmail: ' alice@leap.com ',
      symbol: 'AAPL'
    }).subscribe();
    const request = http.expectOne(r => r.url === '/api/order/ops/orders');
    expect(request.request.params.keys().sort()).toEqual(['clientEmail', 'from', 'symbol', 'to']);
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

  // Verify the counts take the same filled-in, trimmed fields as the search.
  it('counts orders by the filled-in search fields', () => {
    service.counts({ from: '2026-10-01T00:00:00.000Z', to: '2026-10-02T00:00:00.000Z', clientEmail: ' alice@leap.com ', side: '' })
      .subscribe();
    const request = http.expectOne(r => r.url === '/api/order/ops/orders/counts');
    expect(request.request.params.keys().sort()).toEqual(['clientEmail', 'from', 'to']);
    expect(request.request.params.get('clientEmail')).toBe('alice@leap.com');
    request.flush({ submitted: 0, filled: 0, rejected: 0, awaitingExecution: 0 });
  });

  // Verify the rejections report is asked for the period.
  it('loads rejections by reason for a period', () => {
    service.rejections('2026-10-01T00:00:00.000Z', '2026-10-02T00:00:00.000Z').subscribe();
    const request = http.expectOne(r => r.url === '/api/order/ops/reports/rejections');
    expect(request.request.params.get('from')).toBe('2026-10-01T00:00:00.000Z');
    expect(request.request.params.get('to')).toBe('2026-10-02T00:00:00.000Z');
    request.flush([]);
  });

  // Verify the stuck orders and an order's records come from their own endpoints.
  it('loads stuck orders and an order\'s records', () => {
    service.stuckOrders().subscribe();
    service.records('order-1').subscribe();
    http.expectOne('/api/order/ops/reports/stuck-orders').flush([]);
    const records = http.expectOne('/api/order/ops/orders/order-1');
    expect(records.request.method).toBe('GET');
    records.flush({ order: null, executions: [], cashEntries: [], positionMovements: [] });
  });
});
