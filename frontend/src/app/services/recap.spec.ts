import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { RecapApi } from './recap';
import { RecapSeenService, lastRecapMonth } from './recap-seen';

describe('RecapApi', () => {
  it('fetches the latest recap from reporting-app', () => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    const http = TestBed.inject(HttpTestingController);
    let month: string | undefined;

    TestBed.inject(RecapApi).latest().subscribe(response => month = response.month);
    const request = http.expectOne('/api/reporting/recaps/latest');
    expect(request.request.method).toBe('GET');
    request.flush({ month: '2026-09', generatedAt: '2026-10-01T00:30:00Z', ranked: true, recap: {} });

    expect(month).toBe('2026-09');
    http.verify();
  });
});

describe('lastRecapMonth', () => {
  it('is the month before, in UTC', () => {
    expect(lastRecapMonth(new Date('2026-10-16T12:00:00Z'))).toBe('2026-09');
    // 23:30 on 31 October in New York is already November in UTC.
    expect(lastRecapMonth(new Date('2026-10-31T23:30:00-04:00'))).toBe('2026-10');
  });

  it('wraps January back to December of the year before', () => {
    expect(lastRecapMonth(new Date('2027-01-05T00:00:00Z'))).toBe('2026-12');
  });
});

describe('RecapSeenService', () => {
  beforeEach(() => localStorage.clear());
  afterEach(() => localStorage.clear());

  it('is unseen until last month is marked seen, and remembers it', () => {
    const seen = TestBed.inject(RecapSeenService);
    expect(seen.unseen()).toBe(true);

    seen.markSeen(lastRecapMonth());

    expect(seen.unseen()).toBe(false);
    expect(localStorage.getItem('recap_seen_month')).toBe(lastRecapMonth());
  });

  it('is unseen again once a newer month is out', () => {
    localStorage.setItem('recap_seen_month', '2000-01');

    expect(TestBed.inject(RecapSeenService).unseen()).toBe(true);
  });

  it('still works when storage is unavailable', () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => { throw new Error('blocked'); });
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new Error('blocked'); });
    const seen = TestBed.inject(RecapSeenService);

    seen.markSeen(lastRecapMonth());

    expect(seen.unseen()).toBe(false);
  });
});
