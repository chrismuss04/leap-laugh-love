import { recapPayload } from '../../testing/recap-payload';
import {
  benchBars, cardsFor, formatPoints, formatSignedPercentTo, formatSignedWholeMoney, formatWholeMoney, linePath,
  monthName, monthShort, monthYear, plural
} from './recap-format';

describe('cardsFor', () => {
  it('gives a full recap every card', () => {
    expect(cardsFor(recapPayload())).toEqual(['intro', 'money', 'tickers', 'gainer', 'bench', 'tops']);
  });

  it('leaves out the cards a recap has nothing for', () => {
    expect(cardsFor(recapPayload({ topGainer: null, benchmark: null }))).toEqual(['intro', 'money', 'tickers', 'tops']);
  });

  it('skips the trading cards in a quiet month that still has a return', () => {
    const quiet = recapPayload({ quiet: true, topTickers: [], instrumentsTraded: 0, topSector: null });
    expect(cardsFor(quiet)).toEqual(['intro', 'gainer', 'bench', 'tops']);
  });

  it('skips the benchmark when the client has no return to compare', () => {
    const unpriced = recapPayload({ topGainer: null, tradingReturn: { returnPct: null, pnl: 0, dailyPnl: [] } });
    expect(cardsFor(unpriced)).toEqual(['intro', 'money', 'tickers', 'tops']);
  });

  it('has nothing for a quiet month with nothing priced', () => {
    const empty = recapPayload({ quiet: true, tradingReturn: { returnPct: null, pnl: 0, dailyPnl: [] } });
    expect(cardsFor(empty)).toEqual([]);
  });
});

describe('recap formatting', () => {
  it('names the month in UTC', () => {
    expect(monthName('2026-09')).toBe('September');
    expect(monthShort('2026-12')).toBe('Dec 2026');
    expect(monthYear('2026-01')).toBe('2026');
  });

  it('rounds money to the dollar, signed where asked', () => {
    expect(formatWholeMoney(71169.5)).toBe('$71,170');
    expect(formatSignedWholeMoney(2940.4)).toBe('+$2,940');
    expect(formatSignedWholeMoney(-12.6)).toBe('−$13');
    expect(formatSignedWholeMoney(0.2)).toBe('$0');
  });

  it('signs percentages at the given places, and leaves zero unsigned', () => {
    expect(formatSignedPercentTo(6.84, 1)).toBe('+6.8%');
    expect(formatSignedPercentTo(-12.3, 1)).toBe('−12.3%');
    expect(formatSignedPercentTo(-0.04, 1)).toBe('0.0%');
  });

  it('counts points and things', () => {
    expect(formatPoints(-4.72)).toBe('4.7 points');
    expect(formatPoints(1)).toBe('1.0 point');
    expect(plural(1, 'account')).toBe('1 account');
    expect(plural(1041, 'filled order')).toBe('1,041 filled orders');
  });
});

describe('linePath', () => {
  it('scales the values into the box, highest at the top', () => {
    expect(linePath([0, 5, 10], 10, 20, 80, 60)).toBe('M10.0,80.0L50.0,50.0L90.0,20.0');
  });

  it('draws a flat line across the bottom when nothing moves', () => {
    expect(linePath([3, 3], 0, 0, 10, 10)).toBe('M0.0,10.0L10.0,10.0');
  });

  it('is empty with fewer than two values', () => {
    expect(linePath([], 0, 0, 10, 10)).toBe('');
    expect(linePath([1], 0, 0, 10, 10)).toBe('');
  });
});

describe('benchBars', () => {
  it('fills the larger move and scales the other, by size', () => {
    expect(benchBars(6.8, 3.4)).toEqual({ mine: 100, index: 50 });
    expect(benchBars(-2, 4)).toEqual({ mine: 50, index: 100 });
  });

  it('draws nothing when neither moved', () => {
    expect(benchBars(0, 0)).toEqual({ mine: 0, index: 0 });
  });
});
