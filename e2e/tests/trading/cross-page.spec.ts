import { test, expect, accountId } from '../../fixtures/test';
import { DashboardPage } from '../../pages/dashboard.page';
import { OrderHistoryPage } from '../../pages/order-history.page';
import { HoldingsPage } from '../../pages/holdings.page';
import { personas } from '../../data/users';

test.describe('A trade shows up across the app', () => {
  test.use({ persona: 'trader' });

  test('an order placed on the dashboard is at the top of order history with its fill', async ({ page, trader }) => {
    const symbol = 'AMZN';
    const dashboard = new DashboardPage(page);
    await dashboard.goto();
    await dashboard.search.pick(symbol);
    await dashboard.trade.place(1);
    await expect(dashboard.trade.result).toContainText(`Bought 1 share of ${symbol}`);

    const history = new OrderHistoryPage(page);
    await history.goto();
    expect(await history.row(0)).toMatchObject({ symbol, side: 'BUY', quantity: '1', status: 'FILLED', fills: '1' });

    await history.rows.first().click();
    const fills = history.fills.getByRole('row');
    await expect(fills).toHaveCount(2); // header + one execution
    await expect(fills.nth(1)).toContainText(/\$[\d,]+\.\d{2}/);

    await history.rows.first().click();
    await expect(history.fills).toHaveCount(0);

    // And the holdings page lists it under the account that bought it.
    const holdings = new HoldingsPage(page);
    await holdings.goto();
    await expect(holdings.position(trader.accountNumber, symbol)).toBeVisible();
  });
});

test.describe('Trading from a second account', () => {
  // Shared by every worker, so assert on presence rather than exact before/after counts.
  test.use({ persona: 'multi' });

  test('the chosen account is the one that buys', async ({ page, api, tokenFor }) => {
    const as = api.as(await tokenFor(personas.multi.email));
    const secondAccount = await accountId(as, 'ACC-E2E-M2');

    const dashboard = new DashboardPage(page);
    await dashboard.goto();
    await dashboard.search.pick('MSFT');
    await dashboard.trade.account.selectOption({ label: 'ACC-E2E-M2' });
    await dashboard.trade.expectPriced();
    await dashboard.trade.quantity.fill('1');
    await dashboard.trade.reviewButton.click();
    await expect(dashboard.trade.root.locator('.summary')).toContainText('ACC-E2E-M2');

    const [request] = await Promise.all([
      page.waitForRequest(r => r.url().endsWith('/api/order/orders') && r.method() === 'POST'),
      dashboard.trade.submitButton.click()
    ]);
    expect(request.postDataJSON()).toMatchObject({ accountId: secondAccount, symbol: 'MSFT', side: 'BUY', quantity: 1 });
    await expect(dashboard.trade.result).toContainText('Bought 1 share of MSFT');

    const holdings = new HoldingsPage(page);
    await holdings.goto();
    await expect(holdings.position('ACC-E2E-M2', 'MSFT')).toBeVisible();
  });
});
