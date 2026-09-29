import { test, expect } from '../../fixtures/test';
import { DashboardPage } from '../../pages/dashboard.page';

// A backend being down is simulated at the network layer, so the rest of the stack stays up for
// every other worker.
test.use({ persona: 'trader', allowedConsoleErrors: [/.*/] });

test.describe('When a service is unavailable', () => {
  test('a market data outage is explained and the chart recovers on retry', async ({ page }) => {
    await page.route('**/api/account/portfolio/history**', route => route.fulfill({ status: 503, json: {} }));
    const dashboard = new DashboardPage(page);
    await page.goto('/dashboard');

    await expect(dashboard.chartMessage).toContainText('Market data is unavailable right now.');
    // The rest of the page still works.
    await dashboard.expectLoaded();

    await page.unroute('**/api/account/portfolio/history**');
    await dashboard.chartMessage.getByRole('button', { name: 'Try again' }).click();
    await expect(dashboard.chart).toBeVisible();
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
});
