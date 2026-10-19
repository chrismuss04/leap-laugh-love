import { buildAllocation } from './allocation';
import { PreviewInput, previewTrades } from './trade-preview';

describe('Rebalance trade preview', () => {
  /** An account from holdings (symbol, quantity, price) and cash, with targets in tenths of a percent. */
  function input(holdings: [string, number, number][], cash: number, targets: Record<string, number>): PreviewInput {
    const allocation = buildAllocation(holdings.map(([symbol, quantity, price]) => ({ symbol, name: symbol, quantity, price })), cash,
      { alwaysCash: true });
    return {
      slices: allocation.slices,
      targets,
      positions: holdings.map(([symbol, quantity]) => ({ symbol, quantity })),
      prices: Object.fromEntries(holdings.map(([symbol, , price]) => [symbol, price])),
      cash
    };
  }
  // AAPL $1,500 (50%), MSFT $800 (26.7%), cash $700 (23.3%).
  const account: [string, number, number][] = [['AAPL', 10, 150], ['MSFT', 2, 400]];

  it('lists the whole-share trades the targets take, sells first', () => {
    const preview = previewTrades(input(account, 700, { AAPL: 300, MSFT: 600, cash: 100 }));
    expect(preview.orders.map(o => [o.side, o.quantity, o.symbol, o.estimatedValue])).toEqual([
      ['SELL', 4, 'AAPL', 600], ['BUY', 2, 'MSFT', 800]
    ]);
    expect(preview.bySymbol).toEqual({
      AAPL: { side: 'SELL', quantity: 4, value: 600 }, MSFT: { side: 'BUY', quantity: 2, value: 800 }
    });
    expect([preview.sellTotal, preview.buyTotal, preview.cashAfter]).toEqual([600, 800, 500]);
    expect(preview.balanced).toBe(false);
  });

  it('prices buys at the ask and sells at the bid when quotes are known', () => {
    const preview = previewTrades({
      ...input(account, 700, { AAPL: 300, MSFT: 600, cash: 100 }),
      quoteOffsets: { AAPL: { ask: 0.1, bid: -0.1 }, MSFT: { ask: 0.5, bid: -0.5 } }
    });
    expect(preview.orders.map(o => o.quotedPrice)).toEqual([149.9, 400.5]);
  });

  it('measures drift as the share of the account that would change hands', () => {
    const preview = previewTrades(input(account, 700, { AAPL: 300, MSFT: 600, cash: 100 }));
    // |50 - 30| + |26.7 - 60| + |23.3 - 10| = 66.7, half of which moves.
    expect(preview.drift).toBeCloseTo(1 / 3, 6);
  });

  it("says 'Already balanced' with no trades when every slice is within 1% of its target", () => {
    const preview = previewTrades(input(account, 700, { AAPL: 495, MSFT: 272, cash: 233 }));
    expect(preview.balanced).toBe(true);
    expect(preview.orders).toEqual([]);
    // (0.5 + 0.53 + 0.03) / 2 ≈ 0.53% of the account.
    expect(preview.drift).toBeCloseTo(0.00533, 4);
  });

  it('is not balanced once any slice is more than 1% off', () => {
    const preview = previewTrades(input(account, 700, { AAPL: 489, MSFT: 278, cash: 233 }));
    expect(preview.balanced).toBe(false);
  });

  it('sells every share of a slice targeted at 0%', () => {
    const preview = previewTrades(input(account, 700, { AAPL: 0, MSFT: 267, cash: 733 }));
    expect(preview.orders).toEqual([expect.objectContaining({ side: 'SELL', symbol: 'AAPL', quantity: 10 })]);
  });

  it("passes on the engine's warnings, with a hint when there's no cash cushion", () => {
    const preview = previewTrades(input([['A', 10, 100], ['B', 10, 100]], 0, { A: 0, B: 1000, cash: 0 }));
    const warning = preview.warnings.find(w => w.code === 'not-enough-cash')!;
    expect(warning.message).toContain('scaled down');
    expect(warning.hint).toContain('at least 0.5% cash');
    expect(preview.bySymbol['B'].quantity).toBe(9);
  });

  it('warns about trades too small to place', () => {
    // Selling the one 50-cent share is under the $1 minimum, so only the BIG buy is planned.
    const preview = previewTrades(input([['BIG', 10, 100], ['PENNY', 1, 0.5]], 500, { BIG: 1000, PENNY: 0, cash: 0 }));
    expect(preview.warnings).toContainEqual(expect.objectContaining({ code: 'too-small', symbol: 'PENNY', hint: null }));
    expect(preview.orders.map(o => o.symbol)).toEqual(['BIG']);
  });
});
