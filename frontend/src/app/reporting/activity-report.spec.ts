import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting, TestRequest } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { AuthService } from '../services/auth.service';
import { ActivityReport } from '../services/reports';
import { ActivityReportComponent } from './activity-report';

describe('ActivityReportComponent', () => {
  let fixture: ComponentFixture<ActivityReportComponent>;
  let http: HttpTestingController;
  let auth: jasmine.SpyObj<AuthService>;
  const reportUrl = '/api/order/reports/activity';
  const instrumentsUrl = '/api/order/reports/instruments';
  const zero = { tradeCount: 0, buyCount: 0, sellCount: 0, buyValue: 0, sellValue: 0, volume: 0 };

  function report(overrides: Partial<ActivityReport> = {}): ActivityReport {
    return {
      from: '2026-10-02', to: '2026-10-08', granularity: 'DAILY', instrument: null,
      totals: { tradeCount: 3, buyCount: 2, sellCount: 1, buyValue: 1102.5, sellValue: 600, volume: 19 },
      buckets: [
        { periodStart: '2026-10-02', totals: { tradeCount: 2, buyCount: 2, sellCount: 0, buyValue: 1102.5, sellValue: 0, volume: 15 } },
        { periodStart: '2026-10-05', totals: { tradeCount: 1, buyCount: 0, sellCount: 1, buyValue: 0, sellValue: 600, volume: 4 } }
      ],
      ...overrides
    };
  }

  const el = (selector: string): HTMLElement | null => fixture.nativeElement.querySelector(selector);
  const text = (selector: string): string => el(selector)?.textContent?.trim() ?? '';

  /** Starts the page and answers its instrument list, leaving the first report request open. */
  function start(): TestRequest {
    fixture.detectChanges();
    http.expectOne(instrumentsUrl).flush([{ id: 'aapl-id', symbol: 'AAPL' }, { id: 'msft-id', symbol: 'MSFT' }]);
    return http.expectOne(r => r.url === reportUrl);
  }

  function respond(request: TestRequest, body: ActivityReport): void {
    request.flush(body);
    fixture.detectChanges();
  }

  function change(selector: string, value: string): void {
    const input = el(selector) as HTMLInputElement | HTMLSelectElement;
    input.value = value;
    input.dispatchEvent(new Event('change'));
  }

  beforeEach(() => {
    // Thursday 8 October 2026, 22:00 UTC: late enough that a local-time "today" could differ.
    jasmine.clock().install();
    jasmine.clock().mockDate(new Date('2026-10-08T22:00:00Z'));
    auth = jasmine.createSpyObj<AuthService>('AuthService', ['isAuthenticated', 'logout']);
    auth.isAuthenticated.and.returnValue(true);
    TestBed.configureTestingModule({
      imports: [ActivityReportComponent],
      providers: [provideHttpClient(), provideHttpClientTesting(), { provide: AuthService, useValue: auth }]
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(ActivityReportComponent);
  });
  afterEach(() => { http.verify(); fixture.destroy(); jasmine.clock().uninstall(); });

  // Verify signed-out visitors trigger no reporting requests.
  it('skips loading when signed out', () => {
    auth.isAuthenticated.and.returnValue(false);
    fixture.detectChanges();
    http.expectNone(instrumentsUrl);
    http.expectNone(r => r.url === reportUrl);
  });

  // Verify the page opens on a daily report for the last 7 UTC days across all instruments.
  it('loads a default report for the last 7 days', () => {
    const request = start();
    expect(request.request.params.get('from')).toBe('2026-10-02');
    expect(request.request.params.get('to')).toBe('2026-10-08');
    expect(request.request.params.get('granularity')).toBe('DAILY');
    expect(request.request.params.has('instrumentId')).toBeFalse();
    expect(el('.loading')).not.toBeNull();
    respond(request, report());
    expect(el('.loading')).toBeNull();
  });

  // Verify the totals tiles and one breakdown row per bucket are shown.
  it('shows totals and the breakdown', () => {
    respond(start(), report());
    expect(text('[data-testid="total-trades"]')).toBe('3');
    expect(text('[data-testid="total-buys"]')).toBe('2');
    expect(text('[data-testid="total-sells"]')).toBe('1');
    expect(text('[data-testid="total-volume"]')).toBe('19');
    expect(text('[data-testid="activity-totals"]')).toContain('$1,102.50');
    expect(text('[data-testid="activity-totals"]')).toContain('$600.00');
    const rows = fixture.nativeElement.querySelectorAll('[data-testid="activity-row"]');
    expect(rows.length).toBe(2);
    expect(rows[0].textContent).toContain('2 Oct 2026');
    expect(rows[1].textContent).toContain('$600.00');
    expect(el('[data-testid="no-activity"]')).toBeNull();
  });

  // Verify a period with no trading shows "No data available" instead of tiles and a table.
  it('shows no data available for an empty period', () => {
    respond(start(), report({ totals: zero, buckets: [] }));
    expect(text('[data-testid="no-activity"]')).toBe('No data available');
    expect(el('[data-testid="activity-totals"]')).toBeNull();
    expect(el('[data-testid="activity-row"]')).toBeNull();
  });

  // Verify the chosen instrument, dates and weekly breakdown are sent, and the result is labelled.
  it('generates a report for the chosen filters', () => {
    respond(start(), report());
    change('[data-testid="report-instrument"]', 'aapl-id');
    change('[data-testid="report-from"]', '2026-09-01');
    change('[data-testid="report-to"]', '2026-09-30');
    change('[data-testid="report-granularity"]', 'WEEKLY');
    (el('[data-testid="report-generate"]') as HTMLButtonElement).click();

    const request = http.expectOne(r => r.url === reportUrl);
    expect(request.request.params.get('instrumentId')).toBe('aapl-id');
    expect(request.request.params.get('from')).toBe('2026-09-01');
    expect(request.request.params.get('to')).toBe('2026-09-30');
    expect(request.request.params.get('granularity')).toBe('WEEKLY');
    respond(request, report({
      from: '2026-09-01', to: '2026-09-30', granularity: 'WEEKLY', instrument: { id: 'aapl-id', symbol: 'AAPL' },
      buckets: [{ periodStart: '2026-08-31', totals: report().totals }]
    }));

    expect(text('[data-testid="report-scope"]')).toContain('Weekly');
    expect(text('[data-testid="report-scope"]')).toContain('AAPL');
    expect(text('.report-table th')).toBe('Week of');
  });

  // Verify a start date after the end date is caught without asking order-app.
  it('rejects a start date after the end date', () => {
    respond(start(), report());
    change('[data-testid="report-from"]', '2026-10-09');
    (el('[data-testid="report-generate"]') as HTMLButtonElement).click();
    fixture.detectChanges();
    http.expectNone(r => r.url === reportUrl);
    expect(text('.error-message')).toContain('start date must be on or before the end date');
  });

  // Verify order-app's message is shown when it refuses the report, with no stale results.
  it('shows the error when the report fails', () => {
    start().flush({ error: 'BAD_REQUEST', message: 'A report can cover at most 366 days' },
      { status: 400, statusText: 'Bad Request' });
    fixture.detectChanges();
    expect(text('.error-message')).toBe('A report can cover at most 366 days');
    expect(el('[data-testid="no-activity"]')).toBeNull();
    expect(el('[data-testid="activity-totals"]')).toBeNull();
  });

  // Verify the report still loads when the instrument list can't, leaving the filter at "All".
  it('still reports when the instrument list fails', () => {
    fixture.detectChanges();
    http.expectOne(instrumentsUrl).flush({}, { status: 503, statusText: 'Unavailable' });
    respond(http.expectOne(r => r.url === reportUrl), report());
    const options = fixture.nativeElement.querySelectorAll('[data-testid="report-instrument"] option');
    expect(options.length).toBe(1);
    expect(text('[data-testid="total-trades"]')).toBe('3');
  });

  // Verify a second report can't be requested while one is loading.
  it('disables generate while loading', () => {
    const request = start();
    fixture.detectChanges();
    expect((el('[data-testid="report-generate"]') as HTMLButtonElement).disabled).toBeTrue();
    respond(request, report());
    expect((el('[data-testid="report-generate"]') as HTMLButtonElement).disabled).toBeFalse();
  });
});
