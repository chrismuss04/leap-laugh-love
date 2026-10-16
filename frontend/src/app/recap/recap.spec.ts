import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { RecapComponent } from './recap';
import { RecapPayload, RecapResponse } from '../services/recap';
import { RecapSeenService } from '../services/recap-seen';

const LATEST = '/api/reporting/recaps/latest';

function response(): RecapResponse {
  const recap: RecapPayload = {
    month: '2026-09',
    quiet: false,
    moneyMoved: { total: 71169, perMarketDay: 3234.95, marketDays: 22, filledOrders: 41, accounts: 3 },
    topTickers: [{ symbol: 'NVDA', tradedValue: 18240, filledOrders: 9, closes: [100, 110] }],
    topGainer: { symbol: 'NVDA', returnPct: 18.42, pnl: 2100, closes: [100, 110] },
    topGainers: [], biggestLosers: [],
    tradingReturn: { returnPct: 6.8, pnl: 2940, dailyPnl: [] },
    benchmark: { symbol: 'SPX', name: 'S&P 500', returnPct: 2.1, differencePts: 4.7 },
    instrumentsTraded: 23,
    topSector: { sector: 'Information Technology', label: 'Tech', tradedValue: 40000 },
    topPercent: 4
  };
  return { month: '2026-09', generatedAt: '2026-10-01T00:30:00Z', ranked: true, recap };
}

describe('RecapComponent', () => {
  let fixture: ComponentFixture<RecapComponent>;
  let http: HttpTestingController;

  beforeEach(() => {
    localStorage.clear();
    vi.spyOn(window, 'matchMedia').mockReturnValue({ matches: true } as MediaQueryList);   // no auto-advance
    TestBed.configureTestingModule({
      imports: [RecapComponent],
      providers: [provideHttpClient(), provideHttpClientTesting()]
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(RecapComponent);
    fixture.detectChanges();
  });

  afterEach(() => {
    fixture.destroy();
    http.verify();
    localStorage.clear();
  });

  const el = (): HTMLElement => fixture.nativeElement;

  it('loads the recap fonts once, only for this page', () => {
    http.expectOne(LATEST).flush(response());
    const links = document.head.querySelectorAll('link#recap-fonts');
    expect(links).toHaveLength(1);
    expect((links[0] as HTMLLinkElement).href).toContain('Dela+Gothic+One');
  });

  it('shows a skeleton, then the six cards, and marks the month seen', () => {
    expect(el().querySelector('.stage-skeleton')).not.toBeNull();

    http.expectOne(LATEST).flush(response());
    fixture.detectChanges();

    expect(el().querySelector('.stage-skeleton')).toBeNull();
    expect(el().querySelectorAll('.seg')).toHaveLength(6);
    expect(el().querySelector('.stage')!.getAttribute('aria-label')).toBe('September recap, card 1 of 6: Intro');
    expect(el().querySelector('.card h2')!.textContent!.replace(/\s+/g, ' ').trim()).toBe('Your September, recapped.');
    expect(localStorage.getItem('recap_seen_month')).toBe('2026-09');
  });

  it("shows the server's explanation when prices are unavailable, and tries again", () => {
    http.expectOne(LATEST).flush(
      { error: 'RECAP_UNAVAILABLE', message: 'Prices are unavailable. Try again shortly.' },
      { status: 503, statusText: 'Service Unavailable' });
    fixture.detectChanges();

    expect(el().querySelector('[role="alert"]')!.textContent).toContain('Prices are unavailable');
    expect(TestBed.inject(RecapSeenService).unseen()).toBe(true);

    (el().querySelector('.retry') as HTMLButtonElement).click();
    fixture.detectChanges();
    http.expectOne(LATEST).flush(response());
    fixture.detectChanges();

    expect(el().querySelector('[role="alert"]')).toBeNull();
    expect(el().querySelectorAll('.seg')).toHaveLength(6);
  });

  it('falls back to a generic message when the error has none', () => {
    http.expectOne(LATEST).flush(null, { status: 500, statusText: 'Server Error' });
    fixture.detectChanges();

    expect(el().querySelector('[role="alert"]')!.textContent).toContain("Couldn't load your recap");
  });
});
