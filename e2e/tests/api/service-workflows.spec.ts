import { apiTest as test, expect } from '../../fixtures/test';

// Verify real order execution updates account cash, holdings, and history for both trade directions.
test('buy and sell reconcile', async ({ trader }) => {
  const symbol = 'MSFT';
  const cashBefore = await trader.api.buyingPower(trader.accountId);
  const sharesBefore = await trader.api.sharesHeld(trader.accountId, symbol);

  const buy = await trader.api.placeOrder({ accountId: trader.accountId, symbol, side: 'BUY', quantity: 1 });
  expect(buy.execution?.fillQuantity).toBe(1);
  expect(buy.execution?.fillPrice).toBeGreaterThan(0);
  const buyCost = Math.round(buy.execution!.fillPrice! * 100) / 100;
  expect(await trader.api.sharesHeld(trader.accountId, symbol)).toBe(sharesBefore + 1);
  expect(await trader.api.buyingPower(trader.accountId)).toBeCloseTo(cashBefore - buyCost, 2);

  const sell = await trader.api.placeOrder({ accountId: trader.accountId, symbol, side: 'SELL', quantity: 1 });
  expect(sell.execution?.fillQuantity).toBe(1);
  expect(sell.execution?.fillPrice).toBeGreaterThan(0);
  const sellCredit = Math.round(sell.execution!.fillPrice! * 100) / 100;
  expect(await trader.api.sharesHeld(trader.accountId, symbol)).toBe(sharesBefore);
  expect(await trader.api.buyingPower(trader.accountId)).toBeCloseTo(cashBefore - buyCost + sellCredit, 2);

  const history = await trader.api.orderHistory();
  expect(history.content.find(order => order.orderId === buy.orderId)).toMatchObject({ symbol, side: 'BUY', quantity: 1, status: 'FILLED' });
  expect(history.content.find(order => order.orderId === sell.orderId)).toMatchObject({ symbol, side: 'SELL', quantity: 1, status: 'FILLED' });
});

// Verify IAM logout revokes access across services while preserving a separate login session.
test('logout revokes across services', async ({ api, tokenFor, trader }) => {
  const session = api.as(await tokenFor(trader.persona.email));
  const paths = [
    '/api/iam/v1/clients/me',
    '/api/account/balance',
    '/api/order/orders/history',
    '/api/marketdata/prices'
  ];
  for (const path of paths) {
    expect((await session.get(path)).status(), `active session: ${path}`).toBe(200);
  }
  expect((await session.post('/api/iam/session/logout')).status()).toBe(204);
  for (const path of paths) {
    expect((await session.get(path)).status(), `revoked session: ${path}`).toBe(401);
    expect((await trader.api.get(path)).status(), `separate session: ${path}`).toBe(200);
  }
});
