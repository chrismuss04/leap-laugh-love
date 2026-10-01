import { apiTest as setup, expect } from '../fixtures/test';
import { PASSWORD, personas, trader } from '../data/users';

// Runs before every other project. Each check goes through the frontend's proxy, so a failure
// names the service that is down instead of leaving 150 tests to time out one by one.

const HOW_TO_START =
  'Start the stack first: `docker compose -f docker-compose.yml -f docker-compose.e2e.yml up -d --build`, ' +
  'or `.\\scripts\\start-local.ps1` then `.\\e2e\\scripts\\seed-local.ps1` on Windows without Docker.';

setup('frontend is serving the app', async ({ request, baseURL }) => {
  const response = await request.get('/').catch(error => {
    throw new Error(`Nothing is answering at ${baseURL}. ${HOW_TO_START}\n${error}`);
  });
  expect(response.status(), `frontend at ${baseURL}`).toBe(200);
});

setup('iam-app signs in a seeded user', async ({ api }) => {
  const response = await api.loginRaw(personas.alice.email, PASSWORD);
  expect(response.status(), `iam-app login (via /api/iam proxy). ${HOW_TO_START}`).toBe(200);
});

setup('the E2E seed is loaded', async ({ api }) => {
  const response = await api.loginRaw(trader(0).email, PASSWORD);
  expect(
    response.status(),
    'e2e.trader.00@leap.test cannot sign in - load e2e/seed/e2e_seed.sql (docker-compose.e2e.yml does it on a fresh volume)'
  ).toBe(200);
});

setup('account-app and order-app answer for a signed-in user', async ({ api, tokenFor }) => {
  const as = api.as(await tokenFor(personas.alice.email));
  expect((await as.get('/api/account/balance')).status(), 'account-app').toBe(200);
  expect((await as.get('/api/order/orders/history')).status(), 'order-app').toBe(200);
});

setup('market data is live', async ({ api, tokenFor }) => {
  const as = api.as(await tokenFor(personas.alice.email));
  // Orders are refused against a quote older than 30s, so a stalled simulation would fail every
  // trading test with a confusing message. Catch it here instead.
  await expect
    .poll(
      async () => {
        const price = await as.latestPrice('AAPL').catch(() => null);
        return price ? Date.now() - Date.parse(price.asOf) : Infinity;
      },
      { message: 'market-data-app should be ticking (AAPL quote under 10s old)', timeout: 30_000 }
    )
    .toBeLessThan(10_000);
});
