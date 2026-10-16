import { OrderPlan, PlanInput, planOrders } from './order-plan';

describe('planOrders', () => {
  const prices = { AAPL: 100, MSFT: 200, NVDA: 50, PENNY: 0.5 };

  /** A plan with no cash buffer unless a spec asks for one, so the arithmetic stays round. */
  const plan = (input: Partial<PlanInput>): OrderPlan =>
    planOrders({ positions: [], prices, cash: 0, targets: [], cashBuffer: 0, ...input });

  const orders = (result: OrderPlan) => result.orders.map(o => `${o.side} ${o.quantity} ${o.symbol}`);
  const codes = (result: OrderPlan) => result.warnings.map(w => w.code);

  it('rebalances to percentage targets, selling before buying', () => {
    // $1,000 of AAPL plus $1,000 cash: a quarter in AAPL and half in MSFT.
    const result = plan({
      positions: [{ symbol: 'AAPL', quantity: 10 }],
      cash: 1000,
      targets: [{ symbol: 'MSFT', percent: 50 }, { symbol: 'AAPL', percent: 25 }]
    });
    expect(orders(result)).toEqual(['SELL 5 AAPL', 'BUY 5 MSFT']);
    expect(result.cashAfter).toBe(500);
    expect(result.allocationAfter).toEqual([
      { symbol: 'AAPL', quantity: 5, value: 500, share: 0.25, targetShare: 0.25 },
      { symbol: 'MSFT', quantity: 5, value: 1000, share: 0.5, targetShare: 0.5 }
    ]);
    expect(result.cashShareAfter).toBe(0.25);
    expect(result.warnings).toEqual([]);
  });

  it('rebalances to dollar and share targets', () => {
    const result = plan({
      positions: [{ symbol: 'AAPL', quantity: 10 }, { symbol: 'NVDA', quantity: 4 }],
      cash: 500,
      targets: [{ symbol: 'AAPL', value: 300 }, { symbol: 'NVDA', quantity: 10 }]
    });
    expect(orders(result)).toEqual(['SELL 7 AAPL', 'BUY 6 NVDA']);
    expect(result.cashAfter).toBe(900);
  });

  it('sells a position to zero', () => {
    const result = plan({ positions: [{ symbol: 'AAPL', quantity: 7 }], targets: [{ symbol: 'AAPL', value: 0 }] });
    expect(result.orders).toEqual([{ symbol: 'AAPL', side: 'SELL', quantity: 7, quotedPrice: 100, estimatedValue: 700 }]);
    expect(result.cashAfter).toBe(700);
    expect(result.allocationAfter).toEqual([{ symbol: 'AAPL', quantity: 0, value: 0, share: 0, targetShare: 0 }]);
    expect(result.cashShareAfter).toBe(1);
  });

  it('never sells more than is held', () => {
    const result = plan({ positions: [{ symbol: 'AAPL', quantity: 3 }], targets: [{ symbol: 'AAPL', quantity: -5 }] });
    expect(orders(result)).toEqual(['SELL 3 AAPL']);
    expect(codes(result)).toEqual(['invalid-target']);
  });

  it('buys a symbol the account does not hold yet', () => {
    const result = plan({ cash: 1000, targets: [{ symbol: 'NVDA', value: 400 }] });
    expect(orders(result)).toEqual(['BUY 8 NVDA']);
    expect(result.cashAfter).toBe(600);
    expect(result.allocationAfter.map(h => h.symbol)).toEqual(['NVDA']);
  });

  it('funds buys from sale proceeds', () => {
    const result = plan({
      positions: [{ symbol: 'AAPL', quantity: 10 }],
      targets: [{ symbol: 'AAPL', quantity: 0 }, { symbol: 'MSFT', quantity: 5 }]
    });
    expect(orders(result)).toEqual(['SELL 10 AAPL', 'BUY 5 MSFT']);
    expect(result.cashAfter).toBe(0);
    expect(result.warnings).toEqual([]);
  });

  describe('without enough cash', () => {
    it('scales every buy down by the same factor and warns', () => {
      // $2,000 of buys on $1,000: half of each, in whole shares.
      const result = plan({ cash: 1000, targets: [{ symbol: 'AAPL', value: 1000 }, { symbol: 'NVDA', value: 1000 }] });
      expect(orders(result)).toEqual(['BUY 5 AAPL', 'BUY 10 NVDA']);
      expect(result.cashAfter).toBe(0);
      expect(codes(result)).toEqual(['not-enough-cash']);
    });

    it('spends what flooring leaves over on the buy that lost the most to it', () => {
      // Factor 800/1100: AAPL 3.64 -> 3 and MSFT 2.18 -> 2 leave $100, which buys AAPL's lost share.
      const result = plan({ cash: 800, targets: [{ symbol: 'AAPL', quantity: 5 }, { symbol: 'MSFT', quantity: 3 }] });
      expect(orders(result)).toEqual(['BUY 4 AAPL', 'BUY 2 MSFT']);
      expect(result.cashAfter).toBe(0);
    });

    it('tops up only while the leftover fits, never past a buy\'s full quantity', () => {
      // Factor 1150/1200: MSFT 0.96 -> 0 lost the most and its share fits; AAPL 9.58 -> 9 then doesn't.
      const result = plan({ cash: 1150, targets: [{ symbol: 'AAPL', quantity: 10 }, { symbol: 'MSFT', quantity: 1 }] });
      expect(orders(result)).toEqual(['BUY 9 AAPL', 'BUY 1 MSFT']);
      expect(result.cashAfter).toBe(50);
    });

    it('buys nothing with no cash at all', () => {
      const result = plan({ cash: -50, targets: [{ symbol: 'AAPL', quantity: 2 }] });
      expect(result.orders).toEqual([]);
      expect(codes(result)).toEqual(['not-enough-cash']);
    });

    it('keeps the cash buffer back for price moves', () => {
      // Exactly $1,000 of AAPL on $1,000 doesn't leave the 0.5% buffer, so one share is dropped.
      const result = planOrders({ positions: [], prices, cash: 1000, targets: [{ symbol: 'AAPL', quantity: 10 }] });
      expect(orders(result)).toEqual(['BUY 9 AAPL']);
      expect(codes(result)).toEqual(['not-enough-cash']);
    });
  });

  describe('rounding', () => {
    it('rounds to whole shares toward the target, leaving the rest in cash', () => {
      const result = plan({ cash: 1000, targets: [{ symbol: 'AAPL', value: 1050 / 2 }, { symbol: 'MSFT', value: 399 }] });
      // $525 of AAPL is 5.25 shares and $399 of MSFT is 1.995: never past the target.
      expect(orders(result)).toEqual(['BUY 5 AAPL', 'BUY 1 MSFT']);
      expect(result.cashAfter).toBe(300);
      const aapl = result.allocationAfter.find(h => h.symbol === 'AAPL')!;
      expect(aapl.targetShare).toBeCloseTo(0.525);
      expect(aapl.share).toBeCloseTo(0.5);
    });

    it('rounds a sell down too, so the position stays at or above its target', () => {
      const result = plan({ positions: [{ symbol: 'AAPL', quantity: 10 }], targets: [{ symbol: 'AAPL', value: 250 }] });
      expect(orders(result)).toEqual(['SELL 7 AAPL']);
    });

    it('is not thrown off by floating-point division', () => {
      const result = plan({ cash: 2000, prices: { X: 0.1 }, targets: [{ symbol: 'X', value: 0.3 }], minOrderValue: 0 });
      expect(orders(result)).toEqual(['BUY 3 X']);
    });
  });

  describe('minimum order size', () => {
    it('drops orders under $1 by default and reports them', () => {
      const result = plan({ cash: 100, targets: [{ symbol: 'PENNY', quantity: 1 }, { symbol: 'NVDA', quantity: 1 }] });
      expect(orders(result)).toEqual(['BUY 1 NVDA']);
      expect(result.warnings).toEqual([{
        code: 'too-small', symbol: 'PENNY', message: 'PENNY would trade under $1, which is too small to trade.'
      }]);
    });

    it('takes a configurable minimum, for sells as well as buys', () => {
      const result = plan({
        positions: [{ symbol: 'NVDA', quantity: 3 }],
        cash: 1000,
        targets: [{ symbol: 'NVDA', quantity: 2 }, { symbol: 'AAPL', quantity: 2 }],
        minOrderValue: 100
      });
      expect(orders(result)).toEqual(['BUY 2 AAPL']);
      expect(result.warnings.map(w => `${w.code} ${w.symbol}`)).toEqual(['too-small NVDA']);
    });

    it('drops a buy that scaling shrank below the minimum', () => {
      // Factor 51/102 halves PENNY to 2 shares, $1 - under the $2 minimum. AAPL rounds to nothing.
      const result = plan({
        cash: 51,
        targets: [{ symbol: 'AAPL', quantity: 1 }, { symbol: 'PENNY', quantity: 4 }],
        minOrderValue: 2
      });
      expect(result.orders).toEqual([]);
      expect(result.warnings.map(w => `${w.code} ${w.symbol}`)).toEqual(['not-enough-cash null', 'too-small PENNY']);
      expect(result.cashAfter).toBe(51);
    });
  });

  it('plans nothing when the account is already on target', () => {
    const result = plan({
      positions: [{ symbol: 'AAPL', quantity: 5 }, { symbol: 'MSFT', quantity: 5 }],
      cash: 500,
      targets: [{ symbol: 'AAPL', percent: 25 }, { symbol: 'MSFT', percent: 50 }]
    });
    expect(result.orders).toEqual([]);
    expect(result.warnings).toEqual([]);
    expect(result.cashAfter).toBe(500);
  });

  it('prices buys at the ask and sells at the bid when quotes are known', () => {
    const result = plan({
      positions: [{ symbol: 'AAPL', quantity: 10 }],
      cash: 1000,
      quoteOffsets: { AAPL: { ask: 0.05, bid: -0.05 }, MSFT: { ask: 0.1, bid: -0.1 } },
      targets: [{ symbol: 'AAPL', quantity: 0 }, { symbol: 'MSFT', quantity: 2 }]
    });
    expect(result.orders).toEqual([
      { symbol: 'AAPL', side: 'SELL', quantity: 10, quotedPrice: 99.95, estimatedValue: 999.5 },
      { symbol: 'MSFT', side: 'BUY', quantity: 2, quotedPrice: 200.1, estimatedValue: 400.2 }
    ]);
    expect(result.cashAfter).toBe(1599.3);
  });

  it('orders by side, then symbol, the same way every time', () => {
    const input: PlanInput = {
      positions: [{ symbol: 'MSFT', quantity: 5 }, { symbol: 'AAPL', quantity: 5 }],
      prices: { ...prices, ZZZ: 10, BBB: 10 },
      cash: 1000,
      cashBuffer: 0,
      targets: [{ symbol: 'ZZZ', quantity: 1 }, { symbol: 'MSFT', quantity: 0 }, { symbol: 'BBB', quantity: 1 }, { symbol: 'AAPL', quantity: 0 }]
    };
    expect(orders(planOrders(input))).toEqual(['SELL 5 AAPL', 'SELL 5 MSFT', 'BUY 1 BBB', 'BUY 1 ZZZ']);
    expect(planOrders(input)).toEqual(planOrders({ ...input, targets: [...input.targets].reverse(), positions: [...input.positions].reverse() }));
  });

  describe('holdings without a target', () => {
    const positions = [{ symbol: 'AAPL', quantity: 2 }, { symbol: 'NVDA', quantity: 4 }];

    it('are left alone by default', () => {
      const result = plan({ positions, cash: 400, targets: [{ symbol: 'MSFT', quantity: 2 }] });
      expect(orders(result)).toEqual(['BUY 2 MSFT']);
      expect(result.allocationAfter.find(h => h.symbol === 'NVDA')).toEqual(
        { symbol: 'NVDA', quantity: 4, value: 200, share: 0.25, targetShare: null });
    });

    it('are sold off when asked', () => {
      const result = plan({ positions, untargeted: 'sell', targets: [{ symbol: 'MSFT', quantity: 2 }] });
      expect(orders(result)).toEqual(['SELL 2 AAPL', 'SELL 4 NVDA', 'BUY 2 MSFT']);
      expect(result.allocationAfter.map(h => h.symbol)).toEqual(['MSFT']);
    });
  });

  it('merges a symbol held twice and lets the last duplicate target win', () => {
    const result = plan({
      positions: [{ symbol: 'AAPL', quantity: 2 }, { symbol: 'AAPL', quantity: 3 }, { symbol: 'MSFT', quantity: 0 }],
      targets: [{ symbol: 'AAPL', quantity: 4 }, { symbol: 'AAPL', quantity: 1 }]
    });
    expect(orders(result)).toEqual(['SELL 4 AAPL']);
  });

  it('scales percentages over 100% back to fit', () => {
    const result = plan({ cash: 1000, targets: [{ symbol: 'AAPL', percent: 100 }, { symbol: 'NVDA', percent: 100 }] });
    expect(orders(result)).toEqual(['BUY 5 AAPL', 'BUY 10 NVDA']);
    expect(codes(result)).toEqual(['targets-over-100']);
    expect(result.allocationAfter.map(h => h.targetShare)).toEqual([0.5, 0.5]);
  });

  describe('missing prices', () => {
    it('leaves out a value or percentage target without a price', () => {
      const result = plan({ cash: 100, targets: [{ symbol: 'NEW', value: 50 }, { symbol: 'NEW2', percent: 10 }] });
      expect(result.orders).toEqual([]);
      expect(result.warnings.map(w => `${w.code} ${w.symbol}`)).toEqual(['no-price NEW', 'no-price NEW2']);
      expect(result.allocationAfter.map(h => h.targetShare)).toEqual([null, 0.1]);
    });

    it('leaves out a share target or a sale without a price', () => {
      const result = plan({
        positions: [{ symbol: 'OLD', quantity: 3 }],
        cash: 100,
        untargeted: 'sell',
        targets: [{ symbol: 'NEW', quantity: 2 }]
      });
      expect(result.orders).toEqual([]);
      expect(codes(result)).toEqual(['no-price', 'no-price']);
    });

    it('treats a non-number target as zero', () => {
      const result = plan({ positions: [{ symbol: 'AAPL', quantity: 1 }], targets: [{ symbol: 'AAPL', value: Number.NaN }] });
      expect(orders(result)).toEqual(['SELL 1 AAPL']);
      expect(codes(result)).toEqual(['invalid-target']);
    });
  });

  it('reports no shares for an empty account', () => {
    const result = plan({ targets: [{ symbol: 'AAPL', percent: 50 }] });
    expect(result.orders).toEqual([]);
    expect(result.cashShareAfter).toBe(0);
    expect(result.allocationAfter).toEqual([{ symbol: 'AAPL', quantity: 0, value: 0, share: 0, targetShare: null }]);
  });
});
