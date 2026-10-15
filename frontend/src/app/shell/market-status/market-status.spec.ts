import { ComponentFixture, TestBed } from '@angular/core/testing';
import { computed, signal } from '@angular/core';
import { MarketStatusComponent } from './market-status';
import { MarketStatusService } from '../../services/market-status';
import { MarketStatus, nextChangePhrase } from '../../shared/market-session';

/** Wednesday 14 October 2026, 12:00 ET. */
const NOON = Date.parse('2026-10-14T16:00:00Z');

const OPEN: MarketStatus = {
  session: 'REGULAR', now: '2026-10-14T16:00:00Z', nextSession: 'AFTER_HOURS',
  nextTransitionAt: '2026-10-14T20:00:00Z', nextRegularOpen: '2026-10-14T16:00:00Z',
  today: {
    date: '2026-10-14', open: true, holiday: null, earlyClose: false,
    preMarketOpen: '2026-10-14T08:00:00Z', regularOpen: '2026-10-14T13:30:00Z',
    regularClose: '2026-10-14T20:00:00Z', afterHoursClose: '2026-10-15T00:00:00Z'
  },
  mode: 'REAL', forced: false
};

describe('MarketStatusComponent', () => {
  let fixture: ComponentFixture<MarketStatusComponent>;
  let market: { status: ReturnType<typeof signal<MarketStatus | null>>; now: ReturnType<typeof signal<number>>;
    start: ReturnType<typeof vi.fn>; stop: ReturnType<typeof vi.fn> };

  beforeEach(async () => {
    const status = signal<MarketStatus | null>(OPEN);
    const now = signal(NOON);
    market = {
      status, now,
      start: vi.fn(), stop: vi.fn()
    };
    await TestBed.configureTestingModule({
      imports: [MarketStatusComponent],
      providers: [{
        provide: MarketStatusService,
        useValue: {
          ...market,
          session: computed(() => status()?.session ?? null),
          nextChange: computed(() => status() ? nextChangePhrase(status()!, now()) : null)
        }
      }]
    }).compileComponents();
    fixture = TestBed.createComponent(MarketStatusComponent);
    fixture.detectChanges();
  });

  const el = () => fixture.nativeElement as HTMLElement;
  const pill = () => el().querySelector<HTMLButtonElement>('[data-testid="market-status"]')!;

  it('starts the status service while mounted', () => {
    expect(market.start).toHaveBeenCalledOnce();
    fixture.destroy();
    expect(market.stop).toHaveBeenCalledOnce();
  });

  it('shows the session and the countdown in the pill', () => {
    expect(pill().getAttribute('data-tone')).toBe('open');
    expect(pill().textContent).toContain('Market open');
    expect(pill().textContent).toContain('Closes in 4h');
  });

  it('announces only the session, not the countdown', () => {
    expect(el().querySelector('[role="status"]')!.textContent!.trim()).toBe('Market open');
  });

  it('shows nothing until the status arrives', () => {
    market.status.set(null);
    fixture.detectChanges();
    expect(pill()).toBeNull();
  });

  it('opens a card with today\'s sessions, marking the current one', () => {
    pill().click();
    fixture.detectChanges();

    expect(pill().getAttribute('aria-expanded')).toBe('true');
    const rows = Array.from(el().querySelectorAll('.sessions li'));
    expect(rows.map(row => row.querySelector('.session-name')!.textContent)).toEqual(['Pre-market', 'Regular hours', 'After-hours']);
    expect(rows[1].classList).toContain('current');
    expect(rows[1].textContent).toContain('9:30 AM – 4:00 PM');
    expect(el().querySelector<HTMLElement>('.now')!.style.left).toBe('50%');
  });

  it('closes the card on Escape', () => {
    pill().click();
    fixture.detectChanges();
    el().querySelector('.market-status')!.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }));
    fixture.detectChanges();
    expect(el().querySelector('[role="dialog"]')).toBeNull();
  });

  it('shows the next open and the holiday when closed', () => {
    market.status.set({
      ...OPEN, session: 'CLOSED', nextSession: 'PRE_MARKET', nextTransitionAt: '2026-11-27T09:00:00Z',
      nextRegularOpen: '2026-11-27T14:30:00Z',
      today: { date: '2026-11-26', open: false, holiday: 'Thanksgiving Day', earlyClose: false,
        preMarketOpen: null, regularOpen: null, regularClose: null, afterHoursClose: null }
    });
    market.now.set(Date.parse('2026-11-26T17:00:00Z'));
    fixture.detectChanges();
    pill().click();
    fixture.detectChanges();

    expect(pill().getAttribute('data-tone')).toBe('closed');
    expect(pill().textContent).toContain('Opens Fri 9:30 AM ET');
    expect(el().querySelector('.timeline')).toBeNull();
    expect(el().querySelector('.note')!.textContent).toContain('Closed today for Thanksgiving Day.');
    expect(el().querySelector('.next-open')!.textContent).toContain('Fri, Nov 27 · 9:30 AM ET');
  });
});
