import { isTradable } from './tradable';

describe('isTradable', () => {
  it('refuses the indices and Bitcoin, which order-app has no instrument for', () => {
    for (const symbol of ['SPX', 'NDX', 'DJI', 'RUT', 'VIX', 'US10Y', 'BTCUSD', 'spx']) {
      expect(isTradable(symbol), symbol).toBe(false);
    }
  });

  it('allows stocks', () => {
    expect(isTradable('AAPL')).toBe(true);
    expect(isTradable('KO')).toBe(true);
  });
});
