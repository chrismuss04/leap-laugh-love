import { test, expect } from '../../fixtures/test';
import { DashboardPage } from '../../pages/dashboard.page';
import { Shell } from '../../pages/shell';

// A backend being down is simulated at the network layer, so the rest of the stack stays up for
// every other worker.
test.use({ persona: 'trader', allowedConsoleErrors: [/.*/] });

test.describe('When a service is unavailable', () => {
  test.beforeEach(async ({ trader }) => {
    if ((await trader.api.sharesHeld(trader.accountId, 'AAPL')) === 0) {
      await trader.api.placeOrder({ accountId: trader.accountId, symbol: 'AAPL', side: 'BUY', quantity: 1 });
    }
  });

  test('portfolio history outage shows a message and recovers on retry', async ({ page }) => {
    await page.route('**/api/account/portfolio/history**', route => route.fulfill({ status: 503, json: {} }));
    const dashboard = new DashboardPage(page);
    await page.goto('/dashboard');

    await expect(dashboard.chartMessage).toContainText('Market data is unavailable right now.');
    // The rest of the page still works.
    await dashboard.expectLoaded();

    await page.unroute('**/api/account/portfolio/history**');
    await dashboard.chartMessage.getByRole('button', { name: 'Try again' }).click();
    await expect(dashboard.chart).toBeVisible();
    await expect(dashboard.chartMessage).toBeHidden();
  });

  test('other history failures get a generic message', async ({ page }) => {
    await page.route('**/api/account/portfolio/history**', route => route.fulfill({ status: 500, json: {} }));
    await page.goto('/dashboard');
    await expect(new DashboardPage(page).chartMessage).toContainText('We couldn’t load your portfolio history.');
  });

  test('an account outage shows a banner and recovers on retry', async ({ page }) => {
    await page.route('**/api/account/balance', route => route.fulfill({ status: 500, json: { message: 'Balances are unavailable' } }));
    const dashboard = new DashboardPage(page);
    await page.goto('/dashboard');

    await expect(dashboard.accountBanner).toContainText('Balances are unavailable');

    await page.unroute('**/api/account/balance');
    await dashboard.accountBanner.getByRole('button', { name: 'Try again' }).click();
    await expect(dashboard.accountBanner).toBeHidden();
    await dashboard.expectLoaded();
  });

  test('a recent-orders outage is contained to its card', async ({ page }) => {
    await page.route('**/api/order/orders/history**', route => route.fulfill({ status: 500, json: {} }));
    const dashboard = new DashboardPage(page);
    await dashboard.goto();

    await expect(dashboard.activity).toContainText('We couldn’t load your recent orders.');
    await expect(dashboard.positionRow('AAPL')).toBeVisible();
  });

  test('a dropped price stream is reported and retried', async ({ page }) => {
    let attempts = 0;
    await page.route('**/api/marketdata/stream**', route => {
      attempts++;
      return route.abort();
    });
    await new DashboardPage(page).goto();

    const shell = new Shell(page);
    await expect(shell.liveStatus).toHaveAttribute('data-status', 'reconnecting');
    await expect(shell.liveStatus).toHaveText('Reconnecting…');
    // Backoff starts at one second, so a second attempt comes quickly.
    await expect.poll(() => attempts, { timeout: 15_000 }).toBeGreaterThan(1);

    await page.unroute('**/api/marketdata/stream**');
    await expect(shell.liveStatus).toHaveAttribute('data-status', 'live', { timeout: 30_000 });
  });

  test('without any price the ticket waits instead of estimating', async ({ page }) => {
    await page.route('**/api/marketdata/stream**', route => route.abort());
    await page.route('**/api/marketdata/prices', route => route.fulfill({ json: [] }));
    const dashboard = new DashboardPage(page);
    await dashboard.goto();

    // With no symbol picked, the ticket opens on the largest holding.
    await expect(dashboard.trade.title).toHaveText('Buy AAPL');
    await expect(dashboard.trade.hint).toHaveText('Waiting for a live price…');
    await dashboard.trade.quantity.fill('1');
    await expect(dashboard.trade.reviewButton).toBeDisabled();
  });
});
