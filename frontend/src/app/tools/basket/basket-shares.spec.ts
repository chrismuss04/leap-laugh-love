import { basketMaxCents, roundShares, useLeftovers } from './basket-shares';

describe('basket shares', () => {
  describe('rounding', () => {
    it('buys whole shares, rounding down, and keeps the rest as leftover', () => {
      const r = roundShares(['AAPL', 'F'], [50000, 2500], { AAPL: 150, F: 12 });
      expect(r.buys.map(b => [b.symbol, b.shares, b.costCents, b.leftoverCents])).toEqual([['AAPL', 3, 45000, 5000], ['F', 2, 2400, 100]]);
      expect(r.investedCents).toBe(47400);
      expect(r.leftoverCents).toBe(5100);
    });

    it('counts an exact amount as whole shares despite floating-point error', () => {
      expect(roundShares(['X'], [150000], { X: 150 }).buys[0].shares).toBe(10);
      expect(roundShares(['X'], [7], { X: 0.07 }).buys[0].shares).toBe(1);
    });

    it('flags a stock whose amount is below one share', () => {
      const r = roundShares(['NVDA'], [10000], { NVDA: 138.2 });
      expect(r.buys[0]).toEqual(expect.objectContaining({ shares: 0, notEnough: true, leftoverCents: 10000 }));
    });

    it('leaves a stock without a price unbought but not flagged', () => {
      const r = roundShares(['NEW'], [10000], {});
      expect(r.buys[0]).toEqual(expect.objectContaining({ price: null, shares: 0, notEnough: false }));
    });
  });

  describe('using the leftover', () => {
    it('buys extra shares a share at a time, cheapest first, round after round', () => {
      // $10 + $30 each: AAPL 1 @ 30 (0 left), F 1 @ 10 (0 left) - then a spare $45 to spend.
      const r = roundShares(['AAPL', 'F'], [3000, 1000], { AAPL: 30, F: 10 });
      const { rounding, extras } = useLeftovers(r, 8500);
      // Round 1: F (10), AAPL (30) -> 5 left; round 2: F doesn't fit.
      expect(extras).toEqual({ F: 1, AAPL: 1 });
      expect(rounding.investedCents).toBe(8000);
      expect(rounding.leftoverCents).toBe(500);
    });

    it('repeats rounds while a share still fits', () => {
      const r = roundShares(['F'], [1000], { F: 10 });
      expect(useLeftovers(r, 4500).extras).toEqual({ F: 3 });
    });

    it('never spends more than the amount chosen, whatever the cash', () => {
      // A $5,000 basket in an account with far more cash: only its own leftover is offered.
      const r = roundShares(['AAPL', 'NVDA', 'MSFT'], [166667, 166667, 166666], { AAPL: 105, NVDA: 103, MSFT: 98 });
      expect(r.leftoverCents).toBe(11100);
      const { rounding, extras } = useLeftovers(r, 500000);
      expect(extras).toEqual({ MSFT: 1 });
      expect(rounding.investedCents).toBeLessThanOrEqual(500000);
      expect(rounding.leftoverCents).toBe(1300);
    });

    it('offers nothing when no share fits', () => {
      const r = roundShares(['AAPL'], [20000], { AAPL: 150 });
      expect(useLeftovers(r, 20000).extras).toEqual({});
    });
  });

  describe('the most a basket can cost', () => {
    it('keeps the order engine\'s cash buffer', () => {
      expect(basketMaxCents(10000, [])).toBe(995024);
    });

    it('counts what the plan\'s other orders spend and raise', () => {
      const others = [{ side: 'BUY' as const, estimatedValue: 1200 }, { side: 'SELL' as const, estimatedValue: 3015 }];
      // (10,000 + 3,015) / 1.005 - 1,200 = 11,750.24
      expect(basketMaxCents(10000, others)).toBe(1175024);
    });

    it('is never below zero', () => {
      expect(basketMaxCents(100, [{ side: 'BUY', estimatedValue: 500 }])).toBe(0);
    });
  });
});
