import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import type { MockedObject } from 'vitest';
import { createSpyObj } from '../../testing/create-spy-obj';
import { RecapResponse, RecapService } from '../services/recap';
import { RECAP_FONTS_URL, RecapComponent } from './recap';
import { recapPayload } from '../../testing/recap-payload';

describe('RecapComponent', () => {
  let recaps: MockedObject<RecapService>;
  let fixture: ComponentFixture<RecapComponent>;

  function response(overrides: Partial<RecapResponse> = {}): RecapResponse {
    return { month: '2026-09', generatedAt: '2026-10-01T00:30:00Z', ranked: true, recap: recapPayload(), ...overrides };
  }

  function create(): RecapComponent {
    fixture = TestBed.createComponent(RecapComponent);
    fixture.detectChanges();
    return fixture.componentInstance;
  }

  function text(): string {
    return (fixture.nativeElement as HTMLElement).textContent ?? '';
  }

  function query<T extends Element = HTMLElement>(selector: string): T | null {
    return (fixture.nativeElement as HTMLElement).querySelector<T>(selector);
  }

  beforeEach(() => {
    recaps = createSpyObj('RecapService', ['latest', 'markSeen']);
    TestBed.configureTestingModule({
      imports: [RecapComponent],
      providers: [provideRouter([]), { provide: RecapService, useValue: recaps }]
    });
  });

  afterEach(() => {
    fixture?.destroy();
    vi.useRealTimers();
  });

  it('loads the recap, starts on the intro and marks the month seen', () => {
    recaps.latest.mockReturnValue(of(response()));
    const component = create();

    expect(component.loading()).toBe(false);
    expect(component.cards()).toEqual(['intro', 'money', 'tickers', 'gainer', 'bench', 'tops']);
    expect(text()).toContain('September,');
    expect(text()).toContain('1 / 6');
    expect(query('.stage')?.getAttribute('aria-label')).toBe('Recap card 1 of 6: Intro');
    expect(recaps.markSeen).toHaveBeenCalledWith('2026-09');
  });

  it('loads the cards’ fonts once, only on the recap page', () => {
    recaps.latest.mockReturnValue(of(response()));
    create();
    create();
    expect(document.head.querySelectorAll(`link[href="${RECAP_FONTS_URL}"]`)).toHaveLength(1);
  });

  it('shows each card’s figures as the story moves on', () => {
    // Headline figures count up; run each count to its end.
    vi.useFakeTimers({ toFake: ['requestAnimationFrame', 'cancelAnimationFrame', 'performance'] });
    recaps.latest.mockReturnValue(of(response()));
    const component = create();
    const step = () => { component.next(); fixture.detectChanges(); vi.advanceTimersByTime(2000); };

    step();
    expect(text()).toContain('My Trading Volume');
    expect(text()).toContain('$71,169');
    expect(text()).toContain('$45,200 bought · $25,969 sold');
    expect(text()).toContain('41 filled orders across 3 accounts');

    step();
    expect(text()).toContain('NVDA');
    expect(text()).toContain('$18,240 traded');
    expect(query('.tile path')).not.toBeNull();

    step();
    expect(text()).toContain('18.42%');
    expect(text()).toContain('top 4% of Leapfolio traders');

    step();
    expect(text()).toContain('+6.8%');
    expect(text()).toContain('+2.1%');
    expect(text()).toContain('You beat the market by 4.7 points in September.');

    step();
    expect(text()).toContain('+$2,940');
    expect(text()).toContain('SBUX');
    expect(text()).toContain('Tech');
    expect(query('.year')?.getAttribute('data-year')).toBe('2026');
  });

  it('says when the market won, and leaves out a ranking the client doesn’t have', () => {
    const recap = recapPayload({
      topPercent: null,
      benchmark: { symbol: 'SPX', name: 'S&P 500', returnPct: 5, differencePts: -2.2 }
    });
    recaps.latest.mockReturnValue(of(response({ recap })));
    const component = create();

    component.index.set(3);
    fixture.detectChanges();
    expect(text()).not.toContain('Leapfolio traders');

    component.index.set(4);
    fixture.detectChanges();
    expect(text()).toContain('The market beat you by 2.2 points in September.');
  });

  it('goes back from the left third of the card and on from the rest', () => {
    recaps.latest.mockReturnValue(of(response()));
    const component = create();
    const stage = query('.stage')!;
    vi.spyOn(stage, 'getBoundingClientRect').mockReturnValue({ left: 0, width: 300 } as DOMRect);

    stage.dispatchEvent(new MouseEvent('click', { clientX: 250 }));
    stage.dispatchEvent(new MouseEvent('click', { clientX: 250 }));
    expect(component.index()).toBe(2);

    stage.dispatchEvent(new MouseEvent('click', { clientX: 50 }));
    expect(component.index()).toBe(1);
  });

  it('moves with the arrow keys and pauses on space', () => {
    recaps.latest.mockReturnValue(of(response()));
    const component = create();
    const stage = query('.stage')!;
    const key = (name: string) => stage.dispatchEvent(new KeyboardEvent('keydown', { key: name }));

    key('ArrowRight');
    expect(component.index()).toBe(1);
    key('ArrowLeft');
    key('ArrowLeft');
    expect(component.index()).toBe(0);

    const paused = component.paused();
    key(' ');
    expect(component.paused()).toBe(!paused);
  });

  it('moves on when the current card’s time runs out, not an old one’s, and not while paused', () => {
    recaps.latest.mockReturnValue(of(response()));
    const component = create();
    component.paused.set(false);

    component.onSegmentEnd(0);
    expect(component.index()).toBe(1);
    component.onSegmentEnd(0);
    expect(component.index()).toBe(1);

    component.paused.set(true);
    component.onSegmentEnd(1);
    expect(component.index()).toBe(1);
  });

  it('stops on the last card, and plays again from the start', () => {
    recaps.latest.mockReturnValue(of(response()));
    const component = create();
    component.paused.set(false);
    component.index.set(5);

    component.next();
    expect(component.index()).toBe(5);
    expect(component.paused()).toBe(true);

    component.togglePause();
    expect(component.index()).toBe(0);
    expect(component.paused()).toBe(false);
  });

  it('plays the story whatever the reduced-motion setting', () => {
    vi.spyOn(window, 'matchMedia').mockReturnValue({ matches: true } as MediaQueryList);
    recaps.latest.mockReturnValue(of(response()));
    const component = create();

    expect(component.paused()).toBe(false);
    const buttons = [...(fixture.nativeElement as HTMLElement).querySelectorAll('.controls button')];
    expect(buttons.map(button => button.textContent?.trim())).toContain('Pause');
  });

  it('notes that an unranked recap gets its ranking later', () => {
    recaps.latest.mockReturnValue(of(response({ ranked: false })));
    create();
    expect(query('[data-testid="recap-unranked"]')).not.toBeNull();
  });

  it('explains a month with nothing to recap instead of playing an empty story', () => {
    const recap = recapPayload({ quiet: true, tradingReturn: { returnPct: null, pnl: 0, dailyPnl: [] } });
    recaps.latest.mockReturnValue(of(response({ recap })));
    create();

    expect(query('[data-testid="recap-empty"]')?.textContent).toContain('Nothing to recap for September');
    expect(query('.stage')).toBeNull();
  });

  it('plays every card in a month with no trades, saying where there’s nothing to show', () => {
    const recap = recapPayload({
      quiet: true,
      moneyMoved: { total: 0, bought: 0, sold: 0, filledOrders: 0, accounts: 0 },
      topTickers: [],
      instrumentsTraded: 0,
      topSector: null
    });
    recaps.latest.mockReturnValue(of(response({ recap })));
    const component = create();
    const show = (card: number) => { component.index.set(card); fixture.detectChanges(); };

    expect(component.cards()).toEqual(['intro', 'money', 'tickers', 'gainer', 'bench', 'tops']);

    show(1);
    expect(text()).toContain('No trades this month');
    expect(text()).not.toContain('filled order');

    show(2);
    expect(text()).toContain('No trades in September, so no top tickers.');
  });

  it('says when nothing the client traded could be priced', () => {
    const recap = recapPayload({
      topGainer: null, topGainers: [], biggestLosers: [], topPercent: null,
      tradingReturn: { returnPct: null, pnl: 0, dailyPnl: [] }
    });
    recaps.latest.mockReturnValue(of(response({ recap })));
    const component = create();
    const show = (card: number) => { component.index.set(card); fixture.detectChanges(); };

    expect(component.cards()).toHaveLength(6);

    show(3);
    expect(text()).toContain('Nothing to rank in September.');
    expect(text()).not.toContain('Leapfolio traders');

    show(4);
    expect(text()).toContain('No trading return to compare in September.');
  });

  it('says when no position gained and when the index has no prices', () => {
    const recap = recapPayload({ topGainer: null, topGainers: [], benchmark: null });
    recaps.latest.mockReturnValue(of(response({ recap })));
    const component = create();
    const show = (card: number) => { component.index.set(card); fixture.detectChanges(); };

    show(3);
    expect(text()).toContain('None of your positions gained in September.');

    show(4);
    expect(text()).toContain('Me vs the S&P 500');
    expect(text()).toContain('The S&P 500’s move isn’t available for September.');
  });

  it('shows the server’s message when prices are down, and retries', () => {
    recaps.latest.mockReturnValue(throwError(() => ({
      status: 503, error: { error: 'RECAP_UNAVAILABLE', message: 'Prices are unavailable. Try again shortly.' }
    })));
    const component = create();
    expect(component.error()).toBe('Prices are unavailable. Try again shortly.');
    expect(query('[data-testid="recap-error"]')).not.toBeNull();

    recaps.latest.mockReturnValue(of(response()));
    query<HTMLButtonElement>('[data-testid="recap-error"] button')!.click();
    fixture.detectChanges();
    expect(component.error()).toBeNull();
    expect(query('.stage')).not.toBeNull();
  });

  it('falls back to its own message when the error has none', () => {
    recaps.latest.mockReturnValue(throwError(() => ({ status: 0 })));
    expect(create().error()).toContain('couldn’t be loaded');
  });
});
