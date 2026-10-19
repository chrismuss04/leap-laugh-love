import { SellInput, SellableHolding, planSells } from './sell-plan';

describe('Raise Cash sell plan', () => {
  // AAPL $1,500 (+50%), MSFT $2,000 (-20%), TSLA $800 (-20%), NVDA $1,000 (+25%); $5,300 in all.
  const holdings: SellableHolding[] = [
    { symbol: 'AAPL', quantity: 10, price: 150, averageCost: 100 },
    { symbol: 'MSFT', quantity: 5, price: 400, averageCost: 500 },
    { symbol: 'TSLA', quantity: 4, price: 200, averageCost: 250 },
    { symbol: 'NVDA', quantity: 20, price: 50, averageCost: 40 }
  ];

  function plan(overrides: Partial<SellInput>) {
    return planSells({ holdings, cash: 1000, target: 2000, strategy: 'winners', ...overrides });
  }

  const sold = (input: Partial<SellInput>) => plan(input).sells.map(s => [s.symbol, s.quantity]);

  describe('winners first', () => {
    it('sells the largest gain first, only as many shares as the target needs', () => {
      const result = plan({ target: 2000 });
      expect(result.sells).toEqual([{ symbol: 'AAPL', quantity: 7, price: 150, proceeds: 1050, realised: 350 }]);
      expect([result.raised, result.cashAfter]).toEqual([1050, 2050]);
    });

    it('sells whole positions in order, then part of the next', () => {
      expect(sold({ target: 3000 })).toEqual([['AAPL', 10], ['NVDA', 10]]);
      expect(plan({ target: 3000 }).raised).toBe(2000);
    });

    it('carries on into the losers once the winners run out, smallest loss first', () => {
      expect(sold({ target: 4000 })).toEqual([['AAPL', 10], ['NVDA', 20], ['MSFT', 2]]);
    });
  });

  describe('losers first', () => {
    it('sells the largest loss first, raising a little over the target in whole shares', () => {
      const result = plan({ strategy: 'losers', target: 2000 });
      expect(result.sells).toEqual([{ symbol: 'MSFT', quantity: 3, price: 400, proceeds: 1200, realised: -300 }]);
      expect([result.target, result.raised, result.cashAfter]).toEqual([2000, 1200, 2200]);
    });

    it('carries on into the winners once the losers run out, smallest gain first', () => {
      expect(sold({ strategy: 'losers', target: 4500 })).toEqual([['MSFT', 5], ['TSLA', 4], ['NVDA', 14]]);
    });
  });

  describe('even', () => {
    it('trims every holding by the same fraction, topping up the one that lost most to rounding', () => {
      // $1,060 of $5,300 is 20%: 2 AAPL, 1 MSFT, 4 NVDA and 0.8 TSLA, which rounds up to 1.
      const result = plan({ strategy: 'even', target: 2060 });
      expect(result.sells.map(s => [s.symbol, s.quantity])).toEqual([['AAPL', 2], ['MSFT', 1], ['NVDA', 4], ['TSLA', 1]]);
      expect(result.raised).toBe(1100);
    });

    it('stops adding shares as soon as the target is met', () => {
      // 10%: 1 AAPL and 2 NVDA raise $250; MSFT's 0.5 tops it up past $530, so TSLA's 0.4 isn't needed.
      const result = plan({ strategy: 'even', target: 1530 });
      expect(result.sells.map(s => [s.symbol, s.quantity])).toEqual([['AAPL', 1], ['MSFT', 1], ['NVDA', 2]]);
      expect(result.raised).toBe(650);
    });

    it('sells everything, and no more, at the maximum', () => {
      const result = plan({ strategy: 'even', target: 6300 });
      expect(result.sells.map(s => [s.symbol, s.quantity])).toEqual([['AAPL', 10], ['MSFT', 5], ['NVDA', 20], ['TSLA', 4]]);
      expect(result.raised).toBe(5300);
    });
  });

  describe('ties', () => {
    it('breaks an equal gain by symbol, whatever order the holdings arrive in', () => {
      const reversed = [...holdings].reverse();
      expect(sold({ holdings: reversed, strategy: 'losers', target: 3500 })).toEqual([['MSFT', 5], ['TSLA', 3]]);
    });

    it('breaks an equal rounding remainder by symbol', () => {
      const pair: SellableHolding[] = [
        { symbol: 'B', quantity: 1, price: 100, averageCost: 100 },
        { symbol: 'A', quantity: 1, price: 100, averageCost: 100 }
      ];
      expect(sold({ holdings: pair, cash: 0, target: 100, strategy: 'even' })).toEqual([['A', 1]]);
    });

    it('puts holdings without an average cost last for both winners and losers', () => {
      const withUnknown = [...holdings, { symbol: 'AMD', quantity: 10, price: 10, averageCost: null }];
      const everything = { holdings: withUnknown, target: 1_000_000 };
      expect(sold({ ...everything, strategy: 'winners' }).map(([symbol]) => symbol)).toEqual(['AAPL', 'NVDA', 'MSFT', 'TSLA', 'AMD']);
      expect(sold({ ...everything, strategy: 'losers' }).map(([symbol]) => symbol)).toEqual(['MSFT', 'TSLA', 'NVDA', 'AAPL', 'AMD']);
    });
  });

  describe('locks', () => {
    it('never sells a locked holding and lowers the maximum by its value', () => {
      const result = plan({ locked: new Set(['AAPL']), target: 2000 });
      expect(result.sells.map(s => [s.symbol, s.quantity])).toEqual([['NVDA', 20]]);
      expect(result.locked).toEqual(['AAPL']);
      expect([result.maxCash, result.fullCash]).toEqual([4800, 6300]);
    });

    it('applies to every strategy', () => {
      const locked = new Set(['MSFT', 'TSLA']);
      for (const strategy of ['even', 'winners', 'losers'] as const) {
        expect(sold({ strategy, locked, target: 6300 }).map(([symbol]) => symbol)).not.toContain('MSFT');
      }
    });
  });

  describe('limits', () => {
    it('stops at the most the unlocked holdings can raise, and says it was capped', () => {
      const result = plan({ locked: new Set(['AAPL']), target: 10_000 });
      expect(result.capped).toBe(true);
      expect([result.target, result.raised, result.cashAfter]).toEqual([4800, 3800, 4800]);
    });

    it('is not capped at exactly the maximum', () => {
      expect(plan({ target: 6300 }).capped).toBe(false);
    });

    it('sells nothing for a target at or below current cash', () => {
      for (const target of [1000, 500]) {
        const result = plan({ target });
        expect(result.sells).toEqual([]);
        expect([result.target, result.raised, result.cashAfter, result.capped]).toEqual([1000, 0, 1000, false]);
      }
    });

    it('starts from buying power, which can be less than cash', () => {
      const result = plan({ cash: 250.5, target: 400 });
      expect(result.sells).toEqual([{ symbol: 'AAPL', quantity: 1, price: 150, proceeds: 150, realised: 50 }]);
      expect(result.cashAfter).toBe(400.5);
    });

    it('has nothing to sell for an account with no holdings', () => {
      const result = plan({ holdings: [], target: 5000 });
      expect(result.sells).toEqual([]);
      expect([result.maxCash, result.fullCash, result.target, result.capped]).toEqual([1000, 1000, 1000, true]);
    });
  });

  describe('free shares', () => {
    it('only sells shares not already promised to open sell orders', () => {
      const onHold = holdings.map(h => (h.symbol === 'AAPL' ? { ...h, onHold: 8 } : h));
      const result = plan({ holdings: onHold, target: 2000 });
      expect(result.sells.map(s => [s.symbol, s.quantity])).toEqual([['AAPL', 2], ['NVDA', 14]]);
      expect(result.fullCash).toBe(5100);
    });

    it('leaves out a holding whose shares are all on hold', () => {
      const allHeld = holdings.map(h => (h.symbol === 'AAPL' ? { ...h, onHold: 10 } : h));
      const result = plan({ holdings: allHeld, target: 6300 });
      expect(result.sells.map(s => s.symbol)).not.toContain('AAPL');
      expect(result.maxCash).toBe(4800);
    });
  });

  describe('realised gain and loss', () => {
    it('totals what the sells realise against average cost', () => {
      // 10 AAPL at +$50 and 10 NVDA at +$10 a share.
      const result = plan({ target: 3000 });
      expect(result.sells.map(s => s.realised)).toEqual([500, 100]);
      expect(result.realised).toBe(600);
    });

    it('nets gains against losses', () => {
      // 5 MSFT at -$100, 4 TSLA at -$50 and 14 NVDA at +$10 a share.
      expect(plan({ strategy: 'losers', target: 4500 }).realised).toBe(-560);
    });

    it('leaves out a sell with no average cost, and is null when none has one', () => {
      const unknown: SellableHolding[] = [
        { symbol: 'A', quantity: 10, price: 10, averageCost: null },
        { symbol: 'B', quantity: 10, price: 10, averageCost: 8 }
      ];
      const both = plan({ holdings: unknown, cash: 0, target: 200, strategy: 'even' });
      expect(both.sells.map(s => [s.symbol, s.realised])).toEqual([['A', null], ['B', 20]]);
      expect(both.realised).toBe(20);
      expect(plan({ holdings: unknown.slice(0, 1), cash: 0, target: 50 }).realised).toBeNull();
    });

    it('is null with nothing to sell', () => {
      expect(plan({ target: 1000 }).realised).toBeNull();
    });
  });

  describe('prices and rounding', () => {
    it('leaves out holdings with no price yet, and names them', () => {
      const withUnpriced = [...holdings, { symbol: 'GOOG', quantity: 3, price: null, averageCost: 120 }];
      const result = plan({ holdings: withUnpriced, target: 6300 });
      expect(result.unpriced).toEqual(['GOOG']);
      expect(result.sells.map(s => s.symbol)).not.toContain('GOOG');
      expect(result.maxCash).toBe(6300);
    });

    it('rounds up to whole shares and keeps totals to the cent', () => {
      // $100 at $33.33 a share takes 4 shares: 3 only raise $99.99.
      const odd: SellableHolding[] = [{ symbol: 'ODD', quantity: 10, price: 33.333, averageCost: 30 }];
      const result = plan({ holdings: odd, cash: 0, target: 100 });
      expect(result.sells).toEqual([{ symbol: 'ODD', quantity: 4, price: 33.33, proceeds: 133.32, realised: 13.32 }]);
      expect(result.raised).toBe(133.32);
    });

    it('keeps the target to the cent', () => {
      expect(plan({ target: 1234.567 }).target).toBe(1234.57);
    });
  });
});
