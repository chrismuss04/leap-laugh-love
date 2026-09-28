import { test, expect } from '../../fixtures/test';
import { DashboardPage, RangeLabel } from '../../pages/dashboard.page';
import { MONEY } from '../../pages/format';

const SYMBOL = 'AAPL';

test.use({ persona: 'trader' });

test.describe('Dashboard', () => {
  let dashboard: DashboardPage;

  test.beforeEach(async ({ page, trader }) => {
    // Every figure on this page is derived from holdings, so make sure there is one.
    if ((await trader.api.sharesHeld(trader.accountId, SYMBOL)) === 0) {
      await trader.api.placeOrder({ accountId: trader.accountId, symbol: SYMBOL, side: 'BUY', quantity: 1 });
    }
    dashboard = new DashboardPage(page);
    await dashboard.goto();
  });

  test('shows the portfolio, its figures and positions @smoke', async ({ trader }) => {
    await expect(dashboard.headline).toHaveText(MONEY);
    await expect(dashboard.accountLabel).toContainText(trader.accountNumber);
    await expect(dashboard.investments).toHaveText(MONEY);

    const row = dashboard.positionRow(SYMBOL);
    await expect(row.locator('.price')).toHaveText(MONEY);
    await expect(row.locator('.value')).toHaveText(MONEY);
  });

  test('buying power matches the account balance', async ({ trader }) => {
    const balance = await trader.api.buyingPower(trader.accountId);
    expect(await dashboard.buyingPowerValue()).toBeCloseTo(balance, 2);
  });

  test('every chart range loads its history', async ({ page }) => {
    const ranges: [string, RangeLabel][] = [
      ['1W', 'Past week'], ['1M', 'Past month'], ['3M', 'Past 3 months'], ['1Y', 'Past year'], ['ALL', 'All time']
    ];
    for (const [code, label] of ranges) {
      const response = page.waitForResponse(r => r.url().includes('/api/account/portfolio/history') && r.url().includes(`range=${code}`));
      await dashboard.range(label).click();
      expect((await response).status(), `history for ${code}`).toBe(200);
      await expect(dashboard.chart).toBeVisible();
    }
  });

  test('searching a symbol opens it in the trade panel', async () => {
    await dashboard.search.pick('MSFT');

    await expect(dashboard.trade.title).toHaveText('Buy MSFT');
    await expect(dashboard.trade.quantity).toBeFocused();
  });
});
