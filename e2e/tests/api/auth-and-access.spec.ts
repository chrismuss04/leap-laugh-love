import { apiTest as test, expect, accountId } from '../../fixtures/test';
import { PASSWORD, personas } from '../../data/users';
import { uniqueRegistration } from '../../data/factories';

// API-level checks the UI can't reach: authentication on every endpoint and clients being unable
// to see or trade each other's accounts. Runs once, not per browser.

const PROTECTED: [method: 'GET' | 'POST', path: string][] = [
  ['GET', '/api/iam/v1/clients/me'],
  ['GET', '/api/account/accounts'],
  ['GET', '/api/account/balance'],
  ['GET', '/api/account/portfolio/history?range=1D'],
  ['GET', '/api/order/orders/history'],
  ['POST', '/api/order/orders'],
  ['GET', '/api/marketdata/prices'],
  ['GET', '/api/marketdata/prices/AAPL'],
  ['GET', '/api/marketdata/prices/AAPL/history'],
  ['GET', '/api/marketdata/stream']
];

test.describe('Authentication', () => {
  for (const [method, path] of PROTECTED) {
    test(`${method} ${path} requires a valid token`, async ({ api }) => {
      expect((await api.send(method, path)).status(), 'no token').toBe(401);
      expect((await api.as('not.a.jwt').send(method, path)).status(), 'garbage token').toBe(401);
      // Signed with some other key: well-formed, but must still be refused.
      const forged =
        'eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIwMDAwMDAwMC0wMDAwLTAwMDAtMDAwMC0wMDAwMDAwMDAwMDAifQ.' +
        'c2lnbmVkLXdpdGgtdGhlLXdyb25nLWtleS1zaWduZWQtd2l0aC10aGU';
      expect((await api.as(forged).send(method, path)).status(), 'forged token').toBe(401);
    });
  }

  test('login and registration are open', async ({ api }) => {
    expect((await api.loginRaw(personas.alice.email, PASSWORD)).status()).toBe(200);
    expect((await api.registerRaw(uniqueRegistration())).status()).toBe(201);
  });

  test('a token identifies its own client', async ({ api, tokenFor }) => {
    const me = await (await api.as(await tokenFor(personas.alice.email)).get('/api/iam/v1/clients/me')).json();
    expect(me).toMatchObject({ email: personas.alice.email, fullName: personas.alice.fullName, experienceLevel: 'ADVANCED' });
  });
});

test.describe('Clients are isolated from each other', () => {
  test('another client\'s account cannot be read', async ({ api, tokenFor, trader }) => {
    const alice = api.as(await tokenFor(personas.alice.email));
    const response = await alice.get(`/api/account/accounts/${trader.accountId}/positions`);
    // 404, not 403: an outsider shouldn't learn that the account exists.
    expect(response.status()).toBe(404);
    expect((await alice.get(`/api/account/accounts/${trader.accountId}`)).status()).toBe(404);
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
      const response = await alice.post(`/api/account/balance/accounts/${trader.accountId}/${kind}`, { amount: 1 });
      expect(response.status(), kind).toBe(404);
    }
    expect(await trader.api.buyingPower(trader.accountId)).toBe(before);
  });

  test('order history only contains the caller\'s own orders', async ({ api, tokenFor }) => {
    const history = api.as(await tokenFor(personas.history.email));
    const { content, totalElements } = await history.orderHistory(0, 50);
    // e2e.history has exactly the 25 seeded orders and never trades.
    expect(totalElements).toBe(25);
    expect(content.every(o => o.status === 'REJECTED')).toBe(true);
  });

  test('a multi-account client sees both accounts and no one else\'s', async ({ api, tokenFor }) => {
    const multi = api.as(await tokenFor(personas.multi.email));
    const numbers = (await multi.accounts()).map(a => a.accountNumber).sort();
    expect(numbers).toEqual(['ACC-E2E-M1', 'ACC-E2E-M2']);
    expect(await accountId(multi, 'ACC-E2E-M2')).toBeTruthy();
  });
});
