import { Candle } from '../services/market-data';
import { HitRateInput, hitRateDisclaimer, hitRateHint } from './price-hit-rate';

function candle(low: number, high: number, open = (low + high) / 2): Candle {
  return { bucketStart: '2026-09-01T00:00:00Z', bucketSeconds: 86400, open, high, low, close: open };
}

const base: HitRateInput = {
  symbol: 'AAPL', side: 'BUY', type: 'LIMIT', limitPrice: null, stopPrice: null, trailPercent: null, trailAmount: null
};

describe('hitRateHint', () => {
  const days = (n: number, low: number, high: number) => Array.from({ length: n }, () => candle(low, high));

  it('counts the days a buy limit was reached from above', () => {
    const candles = [...days(21, 185, 190), ...days(9, 175, 190)];
    const hint = hitRateHint({ ...base, limitPrice: 180 }, candles)!;
    expect(hint.days).toBe(9);
    expect(hint.text).toBe('AAPL traded at or below $180.00 on 9 of the last 30 days.');
  });

  it('counts the days a sell limit was reached from below', () => {
    const hint = hitRateHint({ ...base, side: 'SELL', limitPrice: 200 }, [...days(3, 150, 205), ...days(27, 150, 160)])!;
    expect(hint.text).toBe('AAPL traded at or above $200.00 on 3 of the last 30 days.');
  });

  it('says "of the N days available" when there is less than a month of history', () => {
    const hint = hitRateHint({ ...base, limitPrice: 180 }, days(7, 170, 190))!;
    expect(hint.text).toBe('AAPL traded at or below $180.00 on 7 of the 7 days available.');
  });

  it('looks only at the latest 30 days', () => {
    const hint = hitRateHint({ ...base, limitPrice: 180 }, [...days(10, 100, 100), ...days(30, 190, 200)])!;
    expect(hint.days).toBe(0);
    expect(hint.total).toBe(30);
  });

  it('counts a sell stop on days the low fell to it, and a buy stop on days the high rose to it', () => {
    const candles = [...days(14, 90, 110), ...days(16, 100, 110)];
    expect(hitRateHint({ ...base, side: 'SELL', type: 'STOP', stopPrice: 95 }, candles)!.text)
      .toBe('Your stop would have triggered on 14 of the last 30 days.');
    expect(hitRateHint({ ...base, side: 'BUY', type: 'STOP', stopPrice: 110 }, candles)!.days).toBe(30);
  });

  it('measures a trailing percent against each day\'s range, and marks it approximate', () => {
    const candles = [...days(12, 98, 102), ...days(18, 99.5, 100.5)];
    const hint = hitRateHint({ ...base, type: 'TRAILING_STOP', trailPercent: 2 }, candles)!;
    expect(hint.days).toBe(12);
    expect(hint.text).toBe('AAPL moved more than 2% in a day on 12 of the last 30 days.');
    expect(hint.approximate).toBe(true);
    expect(hitRateDisclaimer(hint)).toContain('not a prediction');
    expect(hitRateDisclaimer(hint)).toContain('approximate');
  });

  it('measures a trailing dollar amount', () => {
    expect(hitRateHint({ ...base, type: 'TRAILING_STOP', trailAmount: 3 }, days(5, 97, 101))!.days).toBe(5);
  });

  it('has nothing to say for a market order, a missing price or no history', () => {
    expect(hitRateHint({ ...base, type: 'MARKET' }, days(30, 1, 2))).toBeNull();
    expect(hitRateHint({ ...base, limitPrice: null }, days(30, 1, 2))).toBeNull();
    expect(hitRateHint({ ...base, limitPrice: 5 }, [])).toBeNull();
  });

  it('always labels it as history, not a forecast', () => {
    const hint = hitRateHint({ ...base, limitPrice: 180 }, days(30, 170, 190))!;
    expect(hitRateDisclaimer(hint)).toBe('Simulated past prices, not a prediction.');
  });
});
