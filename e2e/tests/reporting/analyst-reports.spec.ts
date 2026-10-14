import { test, expect } from '../../fixtures/test';
import { staff } from '../../data/users';

// Analyst reports: the six 12-month reports reporting-app serves from the reporting schema. How
// much seeded history falls in the last 12 complete months depends on the day the suite runs, so
// these check that every report loads - a table or "nothing to report" - never that it has rows.
const PANELS = [
  'Monthly scorecard', 'Client activity', 'Activation', 'Retention by sign-up month',
  'Volume from top clients', 'Orders by value'
];
const REPORTS = ['scorecard', 'client-activity', 'retention', 'activation', 'concentration', 'order-sizes'];

test.describe('Analyst reports', () => {
  test('an analyst sees all six 12-month reports load below Order Insights', async ({ page, tokenFor }) => {
    const token = await tokenFor(staff.analyst.email);
    await page.addInitScript(t => localStorage.setItem('auth_token', t), token);
    await page.goto('/reporting');

    await expect(page.getByRole('heading', { name: 'The last 12 months' })).toBeVisible();
    await expect(page.getByTestId('report-window')).toHaveText(/^\w{3} \d{4} – \w{3} \d{4}$/);
    const reports = page.getByRole('region', { name: 'Business reports' });
    for (const heading of PANELS) {
      const panel = reports.getByRole('region', { name: heading });
      await expect(panel).toHaveAttribute('aria-busy', 'false');
      await expect(panel.getByRole('alert')).toHaveCount(0);
      // A table, or the panel's "nothing to report" message.
      await expect(panel.getByRole('table').or(panel.locator('.panel-body .message')).first()).toBeVisible();
    }
  });

  test('clients and Trading Operations are refused the analyst reports', async ({ request, trader, tokenFor }) => {
    for (const token of [trader.token, await tokenFor(staff.tradingOps.email)]) {
      for (const report of REPORTS) {
        const response = await request.get(`/api/reporting/analyst/${report}`, { headers: { Authorization: `Bearer ${token}` } });
        expect(response.status(), report).toBe(403);
      }
    }
  });
});
