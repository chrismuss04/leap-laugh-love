import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { ActivityReport, ReportsService } from './reports';

describe('ReportsService', () => {
  let service: ReportsService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    service = TestBed.inject(ReportsService);
    http = TestBed.inject(HttpTestingController);
  });
  afterEach(() => http.verify());

  // Verify a report for all instruments sends only the period and granularity.
  it('requests an activity report for all instruments', () => {
    service.getActivityReport({ from: '2026-10-01', to: '2026-10-07', granularity: 'DAILY' }).subscribe();
    const request = http.expectOne(r => r.url === '/api/order/reports/activity');
    expect(request.request.method).toBe('GET');
    expect(request.request.params.keys().sort()).toEqual(['from', 'granularity', 'to']);
    expect(request.request.params.get('from')).toBe('2026-10-01');
    expect(request.request.params.get('to')).toBe('2026-10-07');
    expect(request.request.params.get('granularity')).toBe('DAILY');
    request.flush({});
  });

  // Verify an instrument filter is passed through, and a null one is left out.
  it('sends the instrument filter only when one is chosen', () => {
    service.getActivityReport({ from: '2026-10-01', to: '2026-10-07', granularity: 'WEEKLY', instrumentId: 'aapl-id' })
      .subscribe();
    const filtered = http.expectOne(r => r.url === '/api/order/reports/activity');
    expect(filtered.request.params.get('instrumentId')).toBe('aapl-id');
    expect(filtered.request.params.get('granularity')).toBe('WEEKLY');
    filtered.flush({});

    service.getActivityReport({ from: '2026-10-01', to: '2026-10-07', granularity: 'DAILY', instrumentId: null })
      .subscribe();
    const all = http.expectOne(r => r.url === '/api/order/reports/activity');
    expect(all.request.params.has('instrumentId')).toBeFalse();
    all.flush({});
  });

  // Verify the report reaches the caller as order-app sent it, including an empty period.
  it('returns the report', () => {
    const empty: ActivityReport = {
      from: '2026-10-01', to: '2026-10-07', granularity: 'DAILY', instrument: null,
      totals: { tradeCount: 0, buyCount: 0, sellCount: 0, buyValue: 0, sellValue: 0, volume: 0 },
      buckets: []
    };
    let report: ActivityReport | undefined;
    service.getActivityReport({ from: '2026-10-01', to: '2026-10-07', granularity: 'DAILY' })
      .subscribe(r => report = r);
    http.expectOne(r => r.url === '/api/order/reports/activity').flush(empty);
    expect(report).toEqual(empty);
  });

  // Verify a rejected period reaches the caller with order-app's message.
  it('surfaces a bad request', () => {
    let error: { status: number; error: { message: string } } | undefined;
    service.getActivityReport({ from: '2026-10-07', to: '2026-10-01', granularity: 'DAILY' })
      .subscribe({ next: () => fail('Expected an error'), error: e => error = e });
    http.expectOne(r => r.url === '/api/order/reports/activity')
      .flush({ error: 'BAD_REQUEST', message: 'from must be on or before to' }, { status: 400, statusText: 'Bad Request' });
    expect(error?.status).toBe(400);
    expect(error?.error.message).toBe('from must be on or before to');
  });

  // Verify the instrument list for the filter is loaded from order-app.
  it('loads the instruments', () => {
    let symbols: string[] = [];
    service.getInstruments().subscribe(list => symbols = list.map(i => i.symbol));
    const request = http.expectOne('/api/order/reports/instruments');
    expect(request.request.method).toBe('GET');
    request.flush([{ id: 'aapl-id', symbol: 'AAPL' }, { id: 'msft-id', symbol: 'MSFT' }]);
    expect(symbols).toEqual(['AAPL', 'MSFT']);
  });
});
