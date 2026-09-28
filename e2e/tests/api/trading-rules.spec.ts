import { apiTest as test, expect } from '../../fixtures/test';

// Money rules the backend must enforce whatever the UI allows. Uses this worker's own trader
// account; assertions compare before and after.

test.describe('Orders', () => {
  test('a filled buy moves cash and shares by the fill amount', async ({ trader }) => {
    const cashBefore = await trader.api.buyingPower(trader.accountId);
    const sharesBefore = await trader.api.sharesHeld(trader.accountId, 'MSFT');

    const order = await trader.api.placeOrder({ accountId: trader.accountId, symbol: 'MSFT', side: 'BUY', quantity: 2 });
    const cost = order.execution!.fillPrice! * 2;

    expect(order.accountBalanceAfter!).toBeCloseTo(cashBefore - cost, 1);
    expect(await trader.api.sharesHeld(trader.accountId, 'MSFT')).toBe(sharesBefore + 2);
    expect(await trader.api.buyingPower(trader.accountId)).toBeCloseTo(order.accountBalanceAfter!, 2);
  });

  test('selling more shares than held is rejected and changes nothing', async ({ trader }) => {
    const held = await trader.api.sharesHeld(trader.accountId, 'AAPL');
    const cashBefore = await trader.api.buyingPower(trader.accountId);

    const response = await trader.api.placeOrderRaw({ accountId: trader.accountId, symbol: 'AAPL', side: 'SELL', quantity: held + 1 });
    expect(JSON.stringify(await response.json())).toContain('Insufficient position quantity');

    expect(await trader.api.sharesHeld(trader.accountId, 'AAPL')).toBe(held);
    expect(await trader.api.buyingPower(trader.accountId)).toBe(cashBefore);
  });

  test('a buy beyond buying power is rejected', async ({ trader }) => {
    const response = await trader.api.placeOrderRaw({ accountId: trader.accountId, symbol: 'AAPL', side: 'BUY', quantity: 100_000_000 });
    expect(JSON.stringify(await response.json())).toContain('Insufficient funds');
  });
});

test.describe('Cash movements', () => {
  test('a deposit then an equal withdrawal nets to zero', async ({ trader }) => {
    const before = await trader.api.buyingPower(trader.accountId);

    const deposit = await trader.api.post(`/api/account/balance/accounts/${trader.accountId}/deposit`, { amount: 250.5 });
    expect((await deposit.json()).balanceAfter).toBeCloseTo(before + 250.5, 2);

    const withdrawal = await trader.api.post(`/api/account/balance/accounts/${trader.accountId}/withdrawal`, { amount: 250.5 });
    expect(withdrawal.status()).toBeLessThan(300);
    expect(await trader.api.buyingPower(trader.accountId)).toBeCloseTo(before, 2);
  });

  test('withdrawing more than the balance is refused', async ({ trader }) => {
    const before = await trader.api.buyingPower(trader.accountId);
    const response = await trader.api.post(`/api/account/balance/accounts/${trader.accountId}/withdrawal`, { amount: before + 1 });
    expect(response.status()).toBe(400);
    expect(await trader.api.buyingPower(trader.accountId)).toBe(before);
  });
});
