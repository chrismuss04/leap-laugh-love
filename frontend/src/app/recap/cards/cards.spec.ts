import { Type } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { RecapPayload } from '../../services/recap';
import { IntroCardComponent } from './intro-card';
import { MoneyMovedCardComponent } from './money-moved-card';
import { TheTopsCardComponent } from './the-tops-card';
import { TopGainerCardComponent } from './top-gainer-card';
import { TopTickersCardComponent } from './top-tickers-card';
import { VsSp500CardComponent } from './vs-sp500-card';

/** The mockup's sample month. */
function busyMonth(): RecapPayload {
  return {
    month: '2026-09',
    quiet: false,
    moneyMoved: { total: 71169, perMarketDay: 3234.95, marketDays: 22, filledOrders: 41, accounts: 3 },
    topTickers: [
      { symbol: 'NVDA', tradedValue: 18240, filledOrders: 9, closes: [100, 104, 118] },
      { symbol: 'AAPL', tradedValue: 12905, filledOrders: 6, closes: [200, 198, 201] },
      { symbol: 'BTC/USD', tradedValue: 9870, filledOrders: 2, closes: [] }
    ],
    topGainer: { symbol: 'NVDA', returnPct: 18.42, pnl: 2100, closes: [100, 104, 118] },
    topGainers: [{ symbol: 'NVDA', returnPct: 18.42, pnl: 2100 }, { symbol: 'ORCL', returnPct: 11.9, pnl: 400 }],
    biggestLosers: [{ symbol: 'SBUX', returnPct: -12.3, pnl: -300 }],
    tradingReturn: { returnPct: 6.8, pnl: 2940, dailyPnl: [0, 120, -40, 600, 2940] },
    benchmark: { symbol: 'SPX', name: 'S&P 500', returnPct: 2.1, differencePts: 4.7 },
    instrumentsTraded: 23,
    topSector: { sector: 'Information Technology', label: 'Tech', tradedValue: 40000 },
    topPercent: 4
  };
}

/** A month with no trades, nothing priced and no index: everything that can be empty is. */
function emptyMonth(): RecapPayload {
  return {
    month: '2026-09',
    quiet: true,
    moneyMoved: { total: 0, perMarketDay: 0, marketDays: 22, filledOrders: 0, accounts: 0 },
    topTickers: [],
    topGainer: null,
    topGainers: [],
    biggestLosers: [],
    tradingReturn: { returnPct: null, pnl: 0, dailyPnl: new Array(30).fill(0) },
    benchmark: null,
    instrumentsTraded: 0,
    topSector: null,
    topPercent: null
  };
}

function render<T>(card: Type<T>, recap: RecapPayload): HTMLElement {
  TestBed.configureTestingModule({ imports: [card] });
  const fixture = TestBed.createComponent(card);
  fixture.componentRef.setInput('recap', recap);
  fixture.componentRef.setInput('monthName', 'September');
  fixture.detectChanges();
  return fixture.nativeElement;
}

const text = (el: Element | null): string => (el?.textContent ?? '').replace(/\s+/g, ' ').trim();

describe('Recap cards', () => {
  describe('1 · intro', () => {
    it('names the month and year', () => {
      const el = render(IntroCardComponent, busyMonth());
      expect(text(el.querySelector('.kicker'))).toBe('Leapfolio Recap · Sep 2026');
      expect(text(el.querySelector('h2'))).toBe('Your September, recapped.');
      expect(text(el.querySelector('.sub'))).toContain('What you traded');
    });

    it('says when the month was quiet', () => {
      expect(text(render(IntroCardComponent, emptyMonth()).querySelector('.sub'))).toContain('A quiet month');
    });
  });

  describe('2 · money moved', () => {
    it('shows the total without cents, the daily figure with them, and the counts', () => {
      const el = render(MoneyMovedCardComponent, busyMonth());
      expect(text(el.querySelector('.big'))).toBe('$71,169');
      expect(text(el.querySelector('.sub'))).toBe("That's $3,234.95 per market day");
      expect(text(el.querySelector('.meta'))).toBe('41 filled orders across 3 accounts');
    });

    it('says there were no trades rather than showing zeros per day', () => {
      const el = render(MoneyMovedCardComponent, emptyMonth());
      expect(text(el.querySelector('.big'))).toBe('$0');
      expect(text(el.querySelector('.sub'))).toBe('No trades this month');
      expect(el.querySelector('.meta')).toBeNull();
    });
  });

  describe('3 · top tickers', () => {
    it('ranks each ticker with its sparkline, the leader in the accent', () => {
      const el = render(TopTickersCardComponent, busyMonth());
      const rows = el.querySelectorAll('.tk');
      expect(rows).toHaveLength(3);
      expect(text(rows[0].querySelector('.rk'))).toBe('1');
      expect(text(rows[0].querySelector('.chip'))).toBe('NVDA');
      expect(text(rows[0].querySelector('small'))).toBe('$18,240 traded');
      expect(rows[0].querySelector('path')!.getAttribute('stroke')).toBe('var(--accent)');
      expect(rows[1].querySelector('path')!.getAttribute('stroke')).toBe('var(--ink)');
      expect(rows[2].querySelector('svg')).toBeNull();     // BTC/USD had no prices
      expect(el.querySelector('app-recap-footer')).not.toBeNull();
    });

    it('says there were no trades when nothing traded', () => {
      const el = render(TopTickersCardComponent, emptyMonth());
      expect(el.querySelectorAll('.tk')).toHaveLength(0);
      expect(text(el.querySelector('.none'))).toContain('No trades this month');
    });
  });

  describe('4 · top gainer', () => {
    it('shows the best symbol, its return, its line in the disc, and the ranking it is about', () => {
      const el = render(TopGainerCardComponent, busyMonth());
      expect(text(el.querySelector('.box-name'))).toBe('NVDA');
      expect(text(el.querySelector('.pct'))).toBe('18.42%');
      expect(el.querySelectorAll('.rings path')).toHaveLength(2);
      expect(text(el.querySelector('.rank'))).toBe('Your trading return: top 4% of Leapfolio traders');
      expect(text(el.querySelector('.rank .lead'))).toBe('Your trading return:');
    });

    it('says nothing gained, and leaves out an unranked ranking', () => {
      const month = { ...busyMonth(), topGainer: null, topPercent: null };
      const el = render(TopGainerCardComponent, month);
      expect(text(el.querySelector('.box-name'))).toBe('—');
      expect(text(el.querySelector('.empty-note'))).toBe('None of your positions gained in September.');
      expect(el.querySelector('.pct')).toBeNull();
      expect(el.querySelector('.rank')).toBeNull();
      expect(el.querySelectorAll('.rings path')).toHaveLength(0);
    });

    it('says there is nothing to rank when nothing could be priced', () => {
      expect(text(render(TopGainerCardComponent, emptyMonth()).querySelector('.empty-note')))
        .toBe('Nothing to rank in September.');
    });
  });

  describe('5 · vs the S&P 500', () => {
    it('compares the two returns with bars scaled to the larger, and gives the verdict', () => {
      const el = render(VsSp500CardComponent, busyMonth());
      expect(text(el.querySelector('.me .val'))).toBe('+6.8%');
      expect(text(el.querySelector('.spx .val'))).toBe('+2.1%');
      expect((el.querySelector('.me .bar i') as HTMLElement).style.width).toBe('100%');
      expect(parseFloat((el.querySelector('.spx .bar i') as HTMLElement).style.width)).toBeCloseTo(30.88, 1);
      expect(text(el.querySelector('.verdict'))).toBe('You beat the market by 4.7 points in September.');
    });

    it('says when the market won, or when they matched', () => {
      const behind = { ...busyMonth(), benchmark: { symbol: 'SPX', name: 'S&P 500', returnPct: 9, differencePts: -2.2 } };
      expect(text(render(VsSp500CardComponent, behind).querySelector('.verdict')))
        .toBe('The market beat you by 2.2 points in September.');
      TestBed.resetTestingModule();
      const even = { ...busyMonth(), benchmark: { symbol: 'SPX', name: 'S&P 500', returnPct: 6.8, differencePts: 0.01 } };
      expect(text(render(VsSp500CardComponent, even).querySelector('.verdict'))).toBe('You matched the market in September.');
    });

    it('explains a missing return or a missing index instead of comparing', () => {
      const noReturn = { ...busyMonth(), tradingReturn: { returnPct: null, pnl: 0, dailyPnl: [] },
        benchmark: { symbol: 'SPX', name: 'S&P 500', returnPct: 2.1, differencePts: null } };
      let el = render(VsSp500CardComponent, noReturn);
      expect(text(el.querySelector('.me .val'))).toBe('—');
      expect(el.querySelector('.me .val')!.classList).not.toContain('outlined');
      expect(text(el.querySelector('.verdict'))).toBe('No trading return to compare in September.');
      TestBed.resetTestingModule();

      el = render(VsSp500CardComponent, { ...busyMonth(), benchmark: null });
      expect(el.querySelector('.spx')).toBeNull();
      expect(text(el.querySelector('.verdict'))).toBe("The S&P 500's move isn't available for September.");
    });
  });

  describe('6 · the tops', () => {
    it('shows the year, the P&L chart, the lists and the stats', () => {
      const el = render(TheTopsCardComponent, busyMonth());
      expect(text(el.querySelector('.year'))).toBe('2026');
      expect(el.querySelector('.year')!.getAttribute('data-year')).toBe('2026');
      expect(text(el.querySelector('.sq-label'))).toBe('Trading P&L, September');
      expect(text(el.querySelector('.sq-value'))).toBe('+$2,940');
      expect(el.querySelectorAll('.square path')).toHaveLength(2);
      const [gainers, losers] = Array.from(el.querySelectorAll('.col ol'));
      expect(gainers.querySelector('.n')!.textContent).toBe('1');
      expect(text(gainers.querySelector('li')).replace(/\s/g, '')).toBe('1NVDA+18.4%');
      expect(gainers.querySelectorAll('li')).toHaveLength(2);
      expect(text(losers.querySelector('em'))).toBe('−12.3%');
      expect(Array.from(el.querySelectorAll('.stat .v')).map(text)).toEqual(['23', 'Tech']);
    });

    it('fills empty lists and a missing sector with dashes', () => {
      const el = render(TheTopsCardComponent, emptyMonth());
      expect(Array.from(el.querySelectorAll('.col li')).map(text)).toEqual(['—', '—']);
      expect(Array.from(el.querySelectorAll('.stat .v')).map(text)).toEqual(['0', '—']);
      expect(text(el.querySelector('.sq-value'))).toBe('$0');
      expect(el.querySelectorAll('.square path')).toHaveLength(0);
    });
  });
});
