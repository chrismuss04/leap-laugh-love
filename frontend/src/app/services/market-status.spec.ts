import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { MarketStatusService } from './market-status';
import { MarketStatus } from '../shared/market-session';

const URL = '/api/marketdata/market/status';

function status(now: number, overrides: Partial<MarketStatus> = {}): MarketStatus {
  return {
    session: 'REGULAR', now: new Date(now).toISOString(), nextSession: 'AFTER_HOURS',
    nextTransitionAt: new Date(now + 90_000).toISOString(), nextRegularOpen: new Date(now).toISOString(),
    today: {
      date: '2026-10-14', open: true, holiday: null, earlyClose: false,
      preMarketOpen: null, regularOpen: null, regularClose: null, afterHoursClose: null
    },
    mode: 'REAL', forced: false, ...overrides
  };
}

describe('MarketStatusService', () => {
  let http: HttpTestingController;
  let service: MarketStatusService;

  beforeEach(() => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout', 'setInterval', 'clearInterval', 'Date'] });
    vi.setSystemTime(new Date('2026-10-14T14:00:00Z'));
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    http = TestBed.inject(HttpTestingController);
    service = TestBed.inject(MarketStatusService);
  });

  afterEach(() => {
    service.stop();
    http.verify();
    vi.useRealTimers();
  });

  it('fetches the status on start and exposes the session and what comes next', () => {
    service.start();
    http.expectOne(URL).flush(status(Date.now()));

    expect(service.session()).toBe('REGULAR');
    expect(service.nextChange()).toBe('Closes in 1m');
  });

  it('counts down every second', () => {
    service.start();
    http.expectOne(URL).flush(status(Date.now()));

    vi.advanceTimersByTime(45_000);

    expect(service.nextChange()).toBe('Closes in 45s');
  });

  it('fetches again just after the session changes', () => {
    service.start();
    http.expectOne(URL).flush(status(Date.now()));

    vi.advanceTimersByTime(89_000);
    http.expectNone(URL);
    vi.advanceTimersByTime(2_000);

    http.expectOne(URL).flush(status(Date.now(), { session: 'AFTER_HOURS', nextSession: 'CLOSED' }));
    expect(service.session()).toBe('AFTER_HOURS');
  });

  it('counts by the server clock, not the browser one', () => {
    service.start();
    // The server is five minutes ahead.
    http.expectOne(URL).flush(status(Date.now() + 300_000));

    expect(service.now()).toBeGreaterThanOrEqual(Date.now() + 300_000);
    expect(service.nextChange()).toBe('Closes in 1m');
  });

  it('checks back every few minutes when the session will not change', () => {
    service.start();
    http.expectOne(URL).flush(status(Date.now(), { mode: 'ALWAYS_OPEN', nextSession: null, nextTransitionAt: null }));

    vi.advanceTimersByTime(MarketStatusService.REFRESH_MS);

    http.expectOne(URL).flush(status(Date.now()));
  });

  it('keeps the last status and retries after a failure', () => {
    service.start();
    http.expectOne(URL).flush(null, { status: 503, statusText: 'Unavailable' });
    expect(service.status()).toBeNull();

    vi.advanceTimersByTime(MarketStatusService.RETRY_MS);

    http.expectOne(URL).flush(status(Date.now()));
    expect(service.session()).toBe('REGULAR');
  });

  it('shares one poll between users and stops with the last', () => {
    service.start();
    service.start();
    http.expectOne(URL).flush(status(Date.now()));

    service.stop();
    vi.advanceTimersByTime(91_000);
    http.expectOne(URL).flush(status(Date.now()));

    service.stop();
    vi.advanceTimersByTime(MarketStatusService.REFRESH_MS * 2);
    http.expectNone(URL);
  });
});
