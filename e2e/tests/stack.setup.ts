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
  // order-app refuses an order priced against a quote more than 5 seconds old, so stalled or
  // lagging quotes would fail every trading test with a confusing rejection. Catch it here
  // instead. This reads /quotes, what order-app prices from, rather than /prices: that is the
  // simulator's in-memory price and stays current even while quotes are not being stored.
  // Every symbol the suite trades, with a margin under the 5s limit.
  for (const symbol of ['AAPL', 'MSFT', 'AMZN', 'TSLA']) {
    await expect
      .poll(
        async () => {
          const quote = await as.latestQuote(symbol).catch(() => null);
          return quote ? Date.now() - Date.parse(quote.quoteTimestamp) : Infinity;
        },
        { message: `market-data-app should be storing quotes (${symbol} quote under 2s old)`, timeout: 30_000 }
      )
      .toBeLessThan(2_000);
  }
});
