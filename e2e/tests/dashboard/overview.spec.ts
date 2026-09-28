import { test, expect } from '../../fixtures/test';
import { DashboardPage, RangeLabel } from '../../pages/dashboard.page';
import { MONEY } from '../../pages/format';

const SYMBOL = 'AAPL';

test.describe('Dashboard overview', () => {
  test.use({ persona: 'trader' });

  let dashboard: DashboardPage;

  test.beforeEach(async ({ page, trader }) => {
    // Every figure on this page is derived from holdings, so make sure there is one.
    if ((await trader.api.sharesHeld(trader.accountId, SYMBOL)) === 0) {
      await trader.api.placeOrder({ accountId: trader.accountId, symbol: SYMBOL, side: 'BUY', quantity: 1 });
    }
    dashboard = new DashboardPage(page);
    await dashboard.goto();
  });

  test('shows the portfolio value, account and key figures @smoke', async ({ trader }) => {
    await expect(dashboard.headline).toHaveText(MONEY);
    await expect(dashboard.accountLabel).toContainText(trader.accountNumber);
    await expect(dashboard.buyingPower).toHaveText(MONEY);
    await expect(dashboard.investments).toHaveText(MONEY);
    await expect(dashboard.totalReturn).toHaveText(/^[+−]?\$[\d,]+\.\d{2}$/);
  });

  test('buying power matches the account balance', async ({ trader }) => {
    const balance = await trader.api.buyingPower(trader.accountId);
    expect(await dashboard.buyingPowerValue()).toBeCloseTo(balance, 2);
  });

  test('the ticker strip shows six live indices', async () => {
    const labels = ['S&P 500', 'Nasdaq 100', 'Dow 30', 'Russell 2000', 'VIX', 'Bitcoin'];
    for (const label of labels) {
      const item = dashboard.tickerStrip.locator('.item').filter({ hasText: label });
      await expect(item.locator('.price')).toHaveText(/^[\d,]+\.\d{2}$/);
    }
  });

  test('lists held positions with price, value and return', async () => {
    const row = dashboard.positionRow(SYMBOL);
    await expect(row).toBeVisible();
    await expect(row.locator('.price')).toHaveText(MONEY);
    await expect(row.locator('.value')).toHaveText(MONEY);
    await expect(row.locator('.sub').first()).toHaveText(/\d+ shares?/);
    await expect(dashboard.positions.locator('.meta')).toHaveText(/\d+ assets?/);
  });

  test('prices update live without a reload', async () => {
    const price = dashboard.positionRow(SYMBOL).locator('.price');
    await expect(price).toHaveText(MONEY);
    const first = await price.textContent();
    // The simulation ticks every second; a minute-long flat line would mean the stream is dead.
    await expect.poll(() => price.textContent(), { timeout: 30_000 }).not.toBe(first);
  });

  test('every chart range loads its own history', async ({ page }) => {
    const ranges: [string, RangeLabel][] = [
      ['1W', 'Past week'], ['1M', 'Past month'], ['3M', 'Past 3 months'], ['1Y', 'Past year'], ['ALL', 'All time'], ['1D', 'Today']
    ];
    await expect(dashboard.range('Today')).toHaveAttribute('aria-selected', 'true');

    for (const [code, label] of ranges) {
      const response = page.waitForResponse(r => r.url().includes('/api/account/portfolio/history') && r.url().includes(`range=${code}`));
      await dashboard.range(label).click();
      expect((await response).status(), `history for ${code}`).toBe(200);
      await expect(dashboard.range(label)).toHaveAttribute('aria-selected', 'true');
      await expect(page.locator('.hero .period')).toHaveText(label);
      await expect(dashboard.chart).toBeVisible();
    }
  });

  test('arrow keys scrub the chart and the headline follows', async ({ page }) => {
    await expect(dashboard.chart).toBeVisible();
    const period = page.locator('.hero .period');
    await expect(period).toHaveText('Today');

    await dashboard.chart.focus();
    await dashboard.chart.press('ArrowLeft');
    // A scrubbed point shows its own timestamp in place of the range name.
    await expect(period).toHaveText(/^[A-Z][a-z]{2} \d{1,2}/);

    await dashboard.chart.blur();
    await expect(period).toHaveText('Today');
  });

  test('clicking a position opens it in the trade panel', async () => {
    await dashboard.positionRow(SYMBOL).getByRole('button', { name: `Trade ${SYMBOL},` }).click();

    await expect(dashboard.trade.title).toHaveText(`Buy ${SYMBOL}`);
    await expect(dashboard.positionRow(SYMBOL)).toHaveClass(/selected/);
  });

  test('recent activity shows the latest orders and links to the full history', async ({ page }) => {
    await expect(dashboard.activityRows().first()).toBeVisible();
    expect(await dashboard.activityRows().count()).toBeLessThanOrEqual(5);

    await dashboard.activity.getByRole('link', { name: 'View all' }).click();
    await expect(page).toHaveURL(/\/orders$/);
  });

  test('the positions card links to holdings by account', async ({ page }) => {
    await dashboard.positions.getByRole('link', { name: /View all holdings/ }).click();
    await expect(page).toHaveURL(/\/holdings$/);
  });
});

test.describe('Dashboard with two accounts', () => {
  test.use({ persona: 'multi' });

  test('the headline counts the accounts and the ticket offers a choice', async ({ page }) => {
    const dashboard = new DashboardPage(page);
    await dashboard.goto();

    await expect(dashboard.accountLabel).toContainText('2 accounts');
    await dashboard.search.pick('MSFT');
    await expect(dashboard.trade.account).toBeVisible();
    // Order is whatever account-app returns; only the set matters here.
    await expect(dashboard.trade.account.locator('option')).toHaveCount(2);
    expect((await dashboard.trade.account.locator('option').allTextContents()).map(t => t.trim()).sort())
      .toEqual(['ACC-E2E-M1', 'ACC-E2E-M2']);
  });
});

test.describe('Dashboard without a trading account', () => {
  test.use({ persona: 'noAccount' });

  test('shows empty states instead of errors', async ({ page }) => {
    const dashboard = new DashboardPage(page);
    await page.goto('/dashboard');

    await expect(dashboard.positions).toContainText("You don't own any investments yet");
    await expect(dashboard.activity).toContainText('No orders yet. Your trades will show up here.');
    await expect(dashboard.accountBanner).toBeHidden();

    await dashboard.search.pick('AAPL');
    await expect(dashboard.trade.hint).toHaveText('No active account to trade from');
    await expect(dashboard.trade.reviewButton).toBeDisabled();
  });
});
