import { apiTest as test, expect } from '../../fixtures/test';

// Order and cash rules enforced by the backend regardless of what the UI allows. Uses this
// worker's own trader account; assertions compare before and after.

test.describe('Orders', () => {
  test('a filled buy moves cash and shares by the fill amount', async ({ trader }) => {
    const cashBefore = await trader.api.buyingPower(trader.accountId);
    const sharesBefore = await trader.api.sharesHeld(trader.accountId, 'MSFT');

    const order = await trader.api.placeOrder({ accountId: trader.accountId, symbol: 'MSFT', side: 'BUY', quantity: 2 });
    const cost = order.execution!.fillPrice! * 2;

    expect(order.execution!.fillQuantity).toBe(2);
    expect(order.accountBalanceAfter!).toBeCloseTo(cashBefore - cost, 1);
    expect(await trader.api.sharesHeld(trader.accountId, 'MSFT')).toBe(sharesBefore + 2);
    expect(await trader.api.buyingPower(trader.accountId)).toBeCloseTo(order.accountBalanceAfter!, 2);
  });

  test('a sell of more shares than held is rejected and changes nothing', async ({ trader }) => {
    const held = await trader.api.sharesHeld(trader.accountId, 'AAPL');
    const cashBefore = await trader.api.buyingPower(trader.accountId);

    const response = await trader.api.placeOrderRaw({ accountId: trader.accountId, symbol: 'AAPL', side: 'SELL', quantity: held + 1 });
    const body = await response.json();
    expect(body.status ?? response.status()).not.toBe('FILLED');
    expect(JSON.stringify(body)).toContain('Insufficient position quantity');

    expect(await trader.api.sharesHeld(trader.accountId, 'AAPL')).toBe(held);
    expect(await trader.api.buyingPower(trader.accountId)).toBe(cashBefore);
  });

  test('a buy beyond buying power is rejected', async ({ trader }) => {
    const response = await trader.api.placeOrderRaw({ accountId: trader.accountId, symbol: 'AAPL', side: 'BUY', quantity: 100_000_000 });
    expect(JSON.stringify(await response.json())).toContain('Insufficient funds');
  });

  for (const quantity of [0, -1]) {
    test(`quantity ${quantity} is refused`, async ({ trader }) => {
      const response = await trader.api.placeOrderRaw({ accountId: trader.accountId, symbol: 'AAPL', side: 'BUY', quantity });
      expect(response.status()).toBe(400);
    });
  }

  test('an unknown symbol is refused', async ({ trader }) => {
    const response = await trader.api.placeOrderRaw({ accountId: trader.accountId, symbol: 'NOPE123', side: 'BUY', quantity: 1 });
    expect(response.status()).toBe(404);
  });

  test('an index symbol can be quoted but not traded', async ({ trader }) => {
    // SPX exists in market data only - it charts on the ticker strip but has no tradable instrument.
    expect((await trader.api.get('/api/marketdata/prices/SPX')).status()).toBe(200);
    const response = await trader.api.placeOrderRaw({ accountId: trader.accountId, symbol: 'SPX', side: 'BUY', quantity: 1 });
    expect(response.status()).toBe(404);
  });
});

test.describe('Cash movements', () => {
  test('a deposit then a withdrawal of the same amount nets to zero', async ({ trader }) => {
    const before = await trader.api.buyingPower(trader.accountId);

    const deposit = await trader.api.post(`/api/account/balance/accounts/${trader.accountId}/deposit`, { amount: 250.5, description: 'e2e' });
    expect(deposit.status()).toBeLessThan(300);
    expect((await deposit.json()).balanceAfter).toBeCloseTo(before + 250.5, 2);

    const withdrawal = await trader.api.post(`/api/account/balance/accounts/${trader.accountId}/withdrawal`, { amount: 250.5, description: 'e2e' });
    expect(withdrawal.status()).toBeLessThan(300);
    expect(await trader.api.buyingPower(trader.accountId)).toBeCloseTo(before, 2);
  });

  test('withdrawing more than the balance is refused', async ({ trader }) => {
    const before = await trader.api.buyingPower(trader.accountId);
    const response = await trader.api.post(`/api/account/balance/accounts/${trader.accountId}/withdrawal`, { amount: before + 1 });
    expect(response.status()).toBe(400);
    expect(await trader.api.buyingPower(trader.accountId)).toBe(before);
  });

  for (const amount of [0, -5, null]) {
    test(`a deposit of ${amount} is refused`, async ({ trader }) => {
      const response = await trader.api.post(`/api/account/balance/accounts/${trader.accountId}/deposit`, { amount });
      expect(response.status()).toBe(400);
    });
  }
});

test.describe('Market data', () => {
  test('history only accepts the stored candle widths', async ({ trader }) => {
    for (const interval of [60, 300, 3600, 86400]) {
      expect((await trader.api.get('/api/marketdata/prices/AAPL/history', { interval })).status(), `interval ${interval}`).toBe(200);
    }
    expect((await trader.api.get('/api/marketdata/prices/AAPL/history', { interval: 120 })).status()).toBe(400);
  });

  test('an unknown symbol has no price', async ({ trader }) => {
    expect((await trader.api.get('/api/marketdata/prices/NOPE123')).status()).toBe(404);
  });
});
