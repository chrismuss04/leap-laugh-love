import { Page } from '@playwright/test';
import { test, expect } from '../../fixtures/test';
import { staff } from '../../data/users';

// Activity Reporting: analysts report on filled orders, which reporting-etl loads into
// reporting.orders from order-events - including the seeded order history.
const utcDay = (daysAgo: number) => new Date(Date.now() - daysAgo * 86_400_000).toISOString().slice(0, 10);
const lastYear = { from: utcDay(365), to: utcDay(0) };

async function openReporting(page: Page, token: string): Promise<void> {
  await page.addInitScript(t => localStorage.setItem('auth_token', t), token);
  await page.goto('/reporting');
}

async function generate(page: Page, from: string, to: string): Promise<void> {
  await page.getByTestId('report-from').fill(from);
  await page.getByTestId('report-to').fill(to);
  await page.getByTestId('report-generate').click();
}

test.describe('Activity reports', () => {
  test('an analyst sees trade totals with a daily and weekly breakdown', async ({ page, request, tokenFor }) => {
    const token = await tokenFor(staff.analyst.email);
    // The ETL loads asynchronously, so wait until the seeded history has reached the read model.
    await expect.poll(async () => {
      const response = await request.get('/api/order/reports/activity',
        { headers: { Authorization: `Bearer ${token}` }, params: lastYear });
      return (await response.json()).totals.tradeCount;
    }, { timeout: 60_000 }).toBeGreaterThan(0);

    await openReporting(page, token);
    await generate(page, lastYear.from, lastYear.to);

    await expect(page.getByTestId('total-trades')).not.toHaveText('0');
    await expect(page.getByTestId('activity-row').first()).toBeVisible();
    await expect(page.getByTestId('no-activity')).toHaveCount(0);

    await page.getByTestId('report-granularity').selectOption('WEEKLY');
    await page.getByTestId('report-generate').click();
    await expect(page.getByRole('columnheader', { name: 'Week of' })).toBeVisible();
  });

  test('a period with no trading shows "No data available"', async ({ page, tokenFor }) => {
    await openReporting(page, await tokenFor(staff.analyst.email));
    await generate(page, '2000-01-01', '2000-01-07');

    await expect(page.getByTestId('no-activity')).toHaveText('No data available');
    await expect(page.getByTestId('activity-totals')).toHaveCount(0);
  });

  test('clients and Trading Operations are refused the report API', async ({ request, trader, tokenFor }) => {
    for (const token of [trader.token, await tokenFor(staff.tradingOps.email)]) {
      const headers = { Authorization: `Bearer ${token}` };
      expect((await request.get('/api/order/reports/activity', { headers, params: lastYear })).status()).toBe(403);
      expect((await request.get('/api/order/reports/instruments', { headers })).status()).toBe(403);
    }
  });
});
