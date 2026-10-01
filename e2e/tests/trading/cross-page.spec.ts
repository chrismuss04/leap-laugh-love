import { test, expect, accountId } from '../../fixtures/test';
import { DashboardPage } from '../../pages/dashboard.page';
import { OrderHistoryPage } from '../../pages/order-history.page';
import { HoldingsPage } from '../../pages/holdings.page';
import { personas } from '../../data/users';

test.describe('A trade shows up across the app', () => {
  test.use({ persona: 'trader' });

  test('an order placed on the dashboard is in order history and holdings @smoke', async ({ page, trader }) => {
    const symbol = 'AMZN';
    const dashboard = new DashboardPage(page);
    await dashboard.goto();
    await dashboard.search.pick(symbol);
    await dashboard.trade.place(1);
    await expect(dashboard.trade.result).toContainText(`Bought 1 share of ${symbol}`);

    const history = new OrderHistoryPage(page);
    await history.goto();
    expect(await history.row(0)).toMatchObject({ symbol, side: 'BUY', quantity: '1', status: 'FILLED', fills: '1' });

    const holdings = new HoldingsPage(page);
    await holdings.goto();
    await expect(holdings.position(trader.accountNumber, symbol)).toBeVisible();
  });
});

test.describe('Trading from a second account', () => {
  // Shared by every worker, so assert on presence rather than exact before/after counts.
  test.use({ persona: 'multi' });

  test('the chosen account is the one that buys', async ({ page, api, tokenFor }) => {
    const secondAccount = await accountId(api.as(await tokenFor(personas.multi.email)), 'ACC-E2E-M2');

    const dashboard = new DashboardPage(page);
    await dashboard.goto();
    await dashboard.search.pick('MSFT');
    await dashboard.trade.account.selectOption({ label: 'ACC-E2E-M2' });
    await dashboard.trade.expectPriced();
    await dashboard.trade.quantity.fill('1');
    await dashboard.trade.reviewButton.click();

    const [request] = await Promise.all([
      page.waitForRequest(r => r.url().endsWith('/api/order/orders') && r.method() === 'POST'),
      dashboard.trade.submitButton.click()
    ]);
    expect(request.postDataJSON()).toMatchObject({ accountId: secondAccount, symbol: 'MSFT', side: 'BUY', quantity: 1 });
    await expect(dashboard.trade.result).toContainText('Bought 1 share of MSFT');
  });
});
