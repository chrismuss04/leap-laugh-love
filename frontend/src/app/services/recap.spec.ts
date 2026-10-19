import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { RecapService, lastRecapMonth } from './recap';

describe('lastRecapMonth', () => {
  it('is the UTC month before now, across a year end', () => {
    expect(lastRecapMonth(new Date('2026-10-16T12:00:00Z'))).toBe('2026-09');
    expect(lastRecapMonth(new Date('2027-01-01T00:10:00Z'))).toBe('2026-12');
  });
});

describe('RecapService', () => {
  let http: HttpTestingController;

  function create(): RecapService {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    http = TestBed.inject(HttpTestingController);
    return TestBed.inject(RecapService);
  }

  beforeEach(() => localStorage.clear());
  afterEach(() => {
    http.verify();
    localStorage.clear();
  });

  // Verify the recap is read from reporting-app through the dev proxy's same-origin path.
  it('requests the latest recap', () => {
    let month = '';
    create().latest().subscribe(response => month = response.month);
    const request = http.expectOne('/api/reporting/recaps/latest');
    expect(request.request.method).toBe('GET');
    request.flush({ month: '2026-09' });
    expect(month).toBe('2026-09');
  });

  // Verify the nav's marker shows until last month's recap is opened, and stays off after.
  it('is unseen until last month is marked seen', () => {
    const service = create();
    expect(service.unseen()).toBe(true);

    service.markSeen(lastRecapMonth());
    expect(service.unseen()).toBe(false);
    expect(localStorage.getItem('recap_seen_month')).toBe(lastRecapMonth());
  });

  // Verify a recap seen in an earlier month doesn't hide a newer one.
  it('is unseen again once a newer month is due', () => {
    localStorage.setItem('recap_seen_month', '2000-01');
    expect(create().unseen()).toBe(true);
  });
});
