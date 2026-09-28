import { apiTest as test, expect } from '../../fixtures/test';
import { personas } from '../../data/users';

// Authentication on every endpoint, and clients being unable to see or trade each other's
// accounts - checks the UI can't reach. Runs once, with no browser.

const PROTECTED: [method: 'GET' | 'POST', path: string][] = [
  ['GET', '/api/iam/v1/clients/me'],
  ['GET', '/api/account/accounts'],
  ['GET', '/api/account/balance'],
  ['GET', '/api/account/portfolio/history?range=1D'],
  ['GET', '/api/order/orders/history'],
  ['POST', '/api/order/orders'],
  ['GET', '/api/marketdata/prices'],
  ['GET', '/api/marketdata/stream']
];

// Well-formed, but signed with some other key.
const FORGED =
  'eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIwMDAwMDAwMC0wMDAwLTAwMDAtMDAwMC0wMDAwMDAwMDAwMDAifQ.' +
  'c2lnbmVkLXdpdGgtdGhlLXdyb25nLWtleS1zaWduZWQtd2l0aC10aGU';

test('every protected endpoint refuses a missing, garbage or forged token', async ({ api }) => {
  for (const [method, path] of PROTECTED) {
    expect((await api.send(method, path)).status(), `${method} ${path}, no token`).toBe(401);
    expect((await api.as('not.a.jwt').send(method, path)).status(), `${method} ${path}, garbage token`).toBe(401);
    expect((await api.as(FORGED).send(method, path)).status(), `${method} ${path}, forged token`).toBe(401);
  }
});

test.describe('Clients are isolated from each other', () => {
  test('another client\'s account cannot be read', async ({ api, tokenFor, trader }) => {
    const alice = api.as(await tokenFor(personas.alice.email));
    // 404, not 403: an outsider shouldn't learn that the account exists.
    expect((await alice.get(`/api/account/accounts/${trader.accountId}`)).status()).toBe(404);
    expect((await alice.get(`/api/account/accounts/${trader.accountId}/positions`)).status()).toBe(404);
  });

  test('another client\'s account cannot be traded from', async ({ api, tokenFor, trader }) => {
    const alice = api.as(await tokenFor(personas.alice.email));
    const sharesBefore = await trader.api.sharesHeld(trader.accountId, 'AAPL');

    const response = await alice.placeOrderRaw({ accountId: trader.accountId, symbol: 'AAPL', side: 'BUY', quantity: 1 });
    expect(response.status()).toBeGreaterThanOrEqual(400);
    expect(response.status()).toBeLessThan(500);
    expect(await trader.api.sharesHeld(trader.accountId, 'AAPL')).toBe(sharesBefore);
  });

  test('another client\'s account cannot be deposited to or withdrawn from', async ({ api, tokenFor, trader }) => {
    const alice = api.as(await tokenFor(personas.alice.email));
    const before = await trader.api.buyingPower(trader.accountId);

    for (const kind of ['deposit', 'withdrawal']) {
      expect((await alice.post(`/api/account/balance/accounts/${trader.accountId}/${kind}`, { amount: 1 })).status(), kind).toBe(404);
    }
    expect(await trader.api.buyingPower(trader.accountId)).toBe(before);
  });
});
