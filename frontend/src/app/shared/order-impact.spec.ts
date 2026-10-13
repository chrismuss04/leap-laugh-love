import { concentrationWarning, orderImpact } from './order-impact';

const base = {
  side: 'BUY' as const, quantity: 10, price: 100, marketPrice: 100, sharesOwned: 10, averageCost: 80,
  buyingPower: 5_000, totalValue: 10_000
};

describe('orderImpact', () => {
  it('works out a buy: position, share of the account, cash left and the new average cost', () => {
    const impact = orderImpact(base);
    expect(impact.sharesAfter).toBe(20);
    expect(impact.positionValueAfter).toBe(2_000);
    expect(impact.portfolioShare).toBeCloseTo(0.2);
    expect(impact.cashAfter).toBe(4_000);
    expect(impact.averageCostAfter).toBe(90);
    expect(impact.realized).toBeNull();
    expect(impact.concentrated).toBe(false);
  });

  it('uses the order price, not the market, for cost and average cost', () => {
    const impact = orderImpact({ ...base, price: 90 });
    expect(impact.cashAfter).toBe(4_100);
    expect(impact.averageCostAfter).toBeCloseTo(85);
  });

  it('treats a first purchase as costing exactly the order price', () => {
    const impact = orderImpact({ ...base, sharesOwned: 0, averageCost: null });
    expect(impact.sharesAfter).toBe(10);
    expect(impact.averageCostAfter).toBe(100);
  });

  it('flags a position that becomes too large a share of the account', () => {
    const impact = orderImpact({ ...base, quantity: 30, buyingPower: 9_000 });
    expect(impact.portfolioShare).toBeCloseTo(0.4);
    expect(impact.concentrated).toBe(true);
    expect(concentrationWarning('AAPL', impact.portfolioShare!)).toBe('AAPL would be 40% of your portfolio.');
  });

  it('works out a sell: realized gain, shares left and cash credited', () => {
    const impact = orderImpact({ ...base, side: 'SELL', quantity: 4, price: 110 });
    expect(impact.realized).toBe(120);
    expect(impact.sharesAfter).toBe(6);
    expect(impact.cashAfter).toBe(5_440);
    expect(impact.averageCostAfter).toBe(80);
    expect(impact.concentrated).toBe(false);
  });

  it('reports a realized loss, and no cost left when the position is sold out', () => {
    const impact = orderImpact({ ...base, side: 'SELL', quantity: 10, price: 70 });
    expect(impact.realized).toBe(-100);
    expect(impact.sharesAfter).toBe(0);
    expect(impact.averageCostAfter).toBeNull();
  });

  it('leaves the share of the account unknown without a total value', () => {
    const impact = orderImpact({ ...base, totalValue: null });
    expect(impact.portfolioShare).toBeNull();
    expect(impact.concentrated).toBe(false);
  });

  it('shows the worst case at a bracket buy\'s stop-loss', () => {
    expect(orderImpact({ ...base, stopLossPrice: 95 }).worstCaseLoss).toBe(50);
    expect(orderImpact({ ...base, stopLossPrice: null }).worstCaseLoss).toBeNull();
  });
});
