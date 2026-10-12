import { OpenOrder } from '../services/order';
import {
  describeOrder, distanceLabel, distanceTo, holdsByAccount, orderTypeHint, orderTypeLabel, reservePricePerShare,
  sentenceText, waitingPrice
} from './order-types';

describe('order types', () => {
  const describe_ = (overrides: Partial<Parameters<typeof describeOrder>[0]>) => sentenceText(describeOrder({
    side: 'BUY', type: 'MARKET', symbol: 'AAPL', quantity: 10, limitPrice: null, stopPrice: null,
    timeInForce: 'DAY', ...overrides
  }));

  it('says each order in plain English', () => {
    expect(describe_({})).toBe('Buy 10 shares of AAPL now at the best available price.');
    expect(describe_({ type: 'LIMIT', limitPrice: 145 })).toBe('Buy 10 shares of AAPL at $145.00 or lower, good for today.');
    expect(describe_({ type: 'LIMIT', side: 'SELL', limitPrice: 155, timeInForce: 'GTC' }))
      .toBe('Sell 10 shares of AAPL at $155.00 or higher, good until cancelled.');
    expect(describe_({ type: 'STOP', side: 'SELL', stopPrice: 140, quantity: 1 }))
      .toBe('If AAPL falls to $140.00, sell 1 share at the market price, good for today.');
    expect(describe_({ type: 'STOP_LIMIT', stopPrice: 160, limitPrice: 161 }))
      .toBe('If AAPL rises to $160.00, buy 10 shares at $161.00 or lower, good for today.');
  });

  it('marks the numbers that matter, and copes with blanks while editing', () => {
    const parts = describeOrder({
      side: 'SELL', type: 'STOP', symbol: 'AAPL', quantity: null, limitPrice: null, stopPrice: null, timeInForce: 'DAY'
    });
    expect(parts.filter(p => p.strong).map(p => p.text)).toEqual(['$—', 'shares']);
  });

  it('labels and explains each type for its side', () => {
    expect(orderTypeLabel('STOP_LIMIT')).toBe('Stop limit');
    expect(orderTypeLabel(undefined)).toBe('Market');
    expect(orderTypeHint('STOP', 'SELL')).toContain('stop loss');
    expect(orderTypeHint('LIMIT', 'BUY')).toContain('or lower');
  });

  it('holds a limit, or a stop plus a buffer, per share', () => {
    expect(reservePricePerShare('LIMIT', 145, null)).toBe(145);
    expect(reservePricePerShare('STOP', null, 100)).toBeCloseTo(105);
    expect(reservePricePerShare('MARKET', null, null)).toBeNull();
  });

  it('measures how far the market must move', () => {
    expect(distanceTo(95, 100)).toEqual({ percent: 5, direction: 'fall' });
    expect(distanceTo(110, 100)).toEqual({ percent: 10, direction: 'rise' });
    expect(distanceTo(null, 100)).toBeNull();
    expect(distanceLabel(96.8, 100)).toBe('3.2% below the current price');
    expect(distanceLabel(150, 100)).toBe('50% above the current price');
    expect(distanceLabel(100, 100)).toBe('At the current price');
    // Close to the price, two decimals; measured from a named price.
    expect(distanceLabel(462.83, 462.95, 'the ask')).toBe('0.03% below the ask');
    expect(distanceLabel(462.95, 462.95, 'the ask')).toBe('At the ask');
  });

  const open = (overrides: Partial<OpenOrder>): OpenOrder => ({
    orderId: 'o1', accountId: 'a1', symbol: 'AAPL', side: 'BUY', quantity: 2, status: 'OPEN', orderType: 'LIMIT',
    limitPrice: 140, stopPrice: null, timeInForce: 'DAY', submittedAt: '', expiresAt: null, triggeredAt: null,
    reservedCash: 280, ...overrides
  });

  it('waits on a stop until it is reached, then on the limit', () => {
    const stopLimit = open({ orderType: 'STOP_LIMIT', stopPrice: 150, limitPrice: 151 });
    expect(waitingPrice(stopLimit)).toBe(150);
    expect(waitingPrice({ ...stopLimit, triggeredAt: '2026-10-12T12:00:00Z' })).toBe(151);
    expect(waitingPrice(open({}))).toBe(140);
  });

  it('adds up holds per account', () => {
    expect(holdsByAccount([
      open({}),
      open({ orderId: 'o2', reservedCash: 20 }),
      open({ orderId: 'o3', side: 'SELL', quantity: 3, reservedCash: null }),
      open({ orderId: 'o4', accountId: 'a2', side: 'SELL', symbol: 'MSFT', quantity: 1, reservedCash: null })
    ])).toEqual({
      a1: { cash: 300, shares: { AAPL: 3 } },
      a2: { cash: 0, shares: { MSFT: 1 } }
    });
  });
});
