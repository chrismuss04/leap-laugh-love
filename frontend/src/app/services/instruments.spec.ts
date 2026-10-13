import { HttpErrorResponse, provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { InstrumentOverview, InstrumentPeriod, InstrumentsService } from './instruments';

describe('InstrumentsService', () => {
  let service: InstrumentsService;
  let http: HttpTestingController;
  const url = '/api/marketdata/instruments';
  const snapshot: InstrumentOverview = {
    period: 'today', periodStart: '2026-10-12T00:00:00Z', asOf: '2026-10-12T12:00:00Z',
    instruments: [{
      symbol: 'INDEX', name: 'Market Index', assetClass: null, currency: null,
      price: 100, asOf: '2026-10-12T11:59:59Z', baselinePrice: null,
      change: null, changePercent: null
    }]
  };

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    service = TestBed.inject(InstrumentsService);
    http = TestBed.inject(HttpTestingController);
  });
  afterEach(() => http.verify());

  it('defaults to today and preserves timestamps and unavailable metadata/performance', () => {
    let result: InstrumentOverview | undefined;
    service.getOverview().subscribe(value => result = value);
    const request = http.expectOne(`${url}?period=today`);
    expect(request.request.method).toBe('GET');
    request.flush(snapshot);
    expect(result).toEqual(snapshot);
  });

  for (const period of ['today', 'week', 'month'] as InstrumentPeriod[]) {
    it(`requests the selected ${period} period`, () => {
      service.getOverview(period).subscribe();
      http.expectOne(`${url}?period=${period}`).flush({ ...snapshot, period });
    });
  }

  it('returns a successful empty universe as data', () => {
    let result: InstrumentOverview | undefined;
    service.getOverview().subscribe(value => result = value);
    http.expectOne(`${url}?period=today`).flush({ ...snapshot, instruments: [] });
    expect(result?.instruments).toEqual([]);
  });

  it('propagates failures and makes a fresh request for retry', () => {
    let failure: HttpErrorResponse | undefined;
    service.getOverview().subscribe({ error: error => failure = error });
    http.expectOne(`${url}?period=today`).flush({ message: 'Unavailable' }, {
      status: 503, statusText: 'Service Unavailable'
    });
    expect(failure?.status).toBe(503);

    let result: InstrumentOverview | undefined;
    service.getOverview().subscribe(value => result = value);
    http.expectOne(`${url}?period=today`).flush(snapshot);
    expect(result).toEqual(snapshot);
  });

  it('propagates network failures instead of reporting an empty universe', () => {
    let failure: HttpErrorResponse | undefined;
    service.getOverview().subscribe({ error: error => failure = error });
    http.expectOne(`${url}?period=today`).error(new ProgressEvent('error'));
    expect(failure?.status).toBe(0);
  });
});
