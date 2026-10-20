import { conditionalPrice, conditionsProblem, isPlainMarket, pickConditions, projectOrders } from './order-plan';

describe('order conditions', () => {
  it('accepts a plain market order, and each type with exactly its own prices', () => {
    expect(conditionsProblem({ side: 'BUY' })).toBeNull();
    expect(conditionsProblem({ side: 'BUY', orderType: 'LIMIT', limitPrice: 180, timeInForce: 'GTC' })).toBeNull();
    expect(conditionsProblem({ side: 'SELL', orderType: 'STOP', stopPrice: 170 })).toBeNull();
    expect(conditionsProblem({ side: 'SELL', orderType: 'STOP_LIMIT', stopPrice: 170, limitPrice: 169 })).toBeNull();
  });

  it('refuses a missing price, a price the type doesn\'t take, and an unknown type', () => {
    expect(conditionsProblem({ side: 'BUY', orderType: 'LIMIT' })).toContain('limit price');
    expect(conditionsProblem({ side: 'BUY', orderType: 'LIMIT', limitPrice: -1 })).toContain('limit price');
    expect(conditionsProblem({ side: 'BUY', limitPrice: 180 })).toContain('Only limit orders');
    expect(conditionsProblem({ side: 'SELL', orderType: 'LIMIT', limitPrice: 180, stopPrice: 170 })).toContain('Only stop orders');
    expect(conditionsProblem({ side: 'BUY', orderType: 'TRAILING_STOP' as never })).toContain("can't be planned");
  });

  it('keeps a market order to the day', () => {
    expect(conditionsProblem({ side: 'BUY', timeInForce: 'GTC' })).toContain('market order');
    expect(conditionsProblem({ side: 'BUY', timeInForce: 'DAY' })).toBeNull();
  });

  it('allows a bracket only on a buy, its take profit above its stop loss', () => {
    expect(conditionsProblem({ side: 'BUY', bracket: { takeProfit: 210, stopLoss: 180 } })).toBeNull();
    expect(conditionsProblem({ side: 'SELL', bracket: { takeProfit: 210, stopLoss: 180 } })).toContain('Only a buy');
    expect(conditionsProblem({ side: 'BUY', bracket: { takeProfit: 180, stopLoss: 180 } })).toContain('above its stop-loss');
    expect(conditionsProblem({ side: 'BUY', bracket: { takeProfit: 210, stopLoss: 0 } })).toContain('above zero');
  });

  it('picks only the conditions an order sets, in cents', () => {
    expect(pickConditions({})).toEqual({});
    expect(pickConditions({ orderType: 'MARKET', timeInForce: 'DAY' })).toEqual({});
    expect(pickConditions({ orderType: 'LIMIT', limitPrice: 180.123, stopPrice: 9, timeInForce: 'GTC',
      bracket: { takeProfit: 210.456, stopLoss: 170.004 } }))
      .toEqual({ orderType: 'LIMIT', limitPrice: 180.12, timeInForce: 'GTC', bracket: { takeProfit: 210.46, stopLoss: 170 } });
  });

  it('tells a plain market order from one with conditions', () => {
    expect(isPlainMarket({})).toBe(true);
    expect(isPlainMarket({ bracket: { takeProfit: 2, stopLoss: 1 } })).toBe(false);
    expect(isPlainMarket({ orderType: 'LIMIT', limitPrice: 1 })).toBe(false);
  });

  it('plans a limit at its limit and a stop at its stop', () => {
    expect(conditionalPrice({})).toBeNull();
    expect(conditionalPrice({ orderType: 'LIMIT', limitPrice: 180 })).toBe(180);
    expect(conditionalPrice({ orderType: 'STOP', stopPrice: 170 })).toBe(170);
    expect(conditionalPrice({ orderType: 'STOP_LIMIT', stopPrice: 170, limitPrice: 169 })).toBe(169);
  });

  it('projects a limit buy at its limit price, needing no quote', () => {
    const projection = projectOrders({
      positions: [], prices: {}, cash: 1000, cashBuffer: 0,
      orders: [{ symbol: 'AAPL', side: 'BUY', quantity: 5, orderType: 'LIMIT', limitPrice: 180 }]
    });
    expect(projection.orders[0]).toEqual({ symbol: 'AAPL', side: 'BUY', quantity: 5, quotedPrice: 180, estimatedValue: 900 });
    expect(projection.cashAfter).toBe(100);
    expect(projection.warnings.map(w => w.code)).not.toContain('no-price');
  });
});
