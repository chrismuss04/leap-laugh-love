import { test, expect } from '../../fixtures/test';
import { OrderHistoryPage } from '../../pages/order-history.page';
import { personas } from '../../data/users';

test.describe('Order history', () => {
  test.describe('seeded demo client', () => {
    test.use({ persona: 'alice' });

    test('lists orders newest first, and a rejected order has no fills @smoke', async ({ page, api, tokenFor }) => {
      const { content } = await api.as(await tokenFor(personas.alice.email)).orderHistory(0, 20);
      const times = content.map(o => Date.parse(o.submittedAt));
      expect(times, 'API order, newest first').toEqual([...times].sort((a, b) => b - a));

      const history = new OrderHistoryPage(page);
      await history.goto();
      await expect(history.rows).toHaveCount(content.length);
      await expect(history.rows.locator('td:nth-child(2)')).toHaveText(content.map(o => o.symbol));

      const rejected = history.rows.filter({ has: page.getByRole('cell', { name: 'REJECTED', exact: true }) }).first();
      await rejected.click();
      await expect(history.fills).toContainText('No fills for this order');
      await expect(rejected.locator('.expand-icon')).toHaveText('▼');
    });

    test('expanding one order collapses the other', async ({ page }) => {
      const history = new OrderHistoryPage(page);
      await history.goto();
      await history.rows.nth(0).click();
      await history.rows.nth(1).click();

      await expect(history.fills).toHaveCount(1);
      await expect(history.rows.nth(0).locator('.expand-icon')).toHaveText('▶');
    });
  });

  test.describe('pagination and filters', () => {
    // 25 seeded orders, one per hour back from when the database was seeded.
    test.use({ persona: 'history' });

    let history: OrderHistoryPage;

    test.beforeEach(async ({ page }) => {
      history = new OrderHistoryPage(page);
      await history.goto();
    });

    test('pages through 20 orders at a time', async () => {
      await expect(history.rows).toHaveCount(20);
      await expect(history.pageInfo).toHaveText('Page 1 of 2');
      await expect(history.previous).toBeDisabled();

      await history.next.click();
      await history.expectSettled();
      await expect(history.pageInfo).toHaveText('Page 2 of 2');
      await expect(history.rows).toHaveCount(5);
      await expect(history.next).toBeDisabled();

      await history.previous.click();
      await history.expectSettled();
      await expect(history.pageInfo).toHaveText('Page 1 of 2');
    });

    test('month and day unlock in order, and each filter is sent to the server', async ({ page, api, tokenFor }) => {
      await expect(history.month).toBeDisabled();
      await expect(history.day).toBeDisabled();

      const { content } = await api.as(await tokenFor(personas.history.email)).orderHistory(0, 1);
      const date = new Date(content[0].submittedAt);
      const [year, month, day] = [date.getUTCFullYear(), date.getUTCMonth() + 1, date.getUTCDate()];

      let request = page.waitForRequest(r => r.url().includes('/orders/history') && r.url().includes(`year=${year}`));
      await history.year.selectOption(String(year));
      await request;
      await expect(history.month).toBeEnabled();
      await expect(history.day).toBeDisabled();

      request = page.waitForRequest(r => r.url().includes(`month=${month}`));
      await history.month.selectOption(String(month));
      await request;
      await expect(history.day).toBeEnabled();
      await expect(history.day.locator('option')).toHaveCount(new Date(year, month, 0).getDate() + 1); // + "All"

      request = page.waitForRequest(r => r.url().includes(`day=${day}`));
      await history.day.selectOption(String(day));
      await request;
      await history.expectSettled();
      await expect(history.rows.first()).toBeVisible();

      await history.clear.click();
      await history.expectSettled();
      await expect(history.year).toHaveValue('');
      await expect(history.month).toBeDisabled();
      await expect(history.clear).toBeHidden();
      await expect(history.pageInfo).toHaveText('Page 1 of 2');
    });

    test('a filter with no matches says so', async () => {
      // The oldest year offered is four years back; the seeded history is all recent.
      const options = await history.year.locator('option').allTextContents();
      await history.year.selectOption(options[options.length - 1].trim());
      await history.expectSettled();

      await expect(history.empty).toHaveText('No orders match this filter');
    });

    test('February offers the right number of days', async () => {
      const options = await history.year.locator('option').allTextContents();
      for (const yearText of options.slice(1).map(o => o.trim())) {
        const year = Number(yearText);
        await history.year.selectOption(yearText);
        await history.month.selectOption('2');
        // Day 0 of March is the last day of February.
        const daysInFebruary = new Date(Date.UTC(year, 2, 0)).getUTCDate();
        await expect(history.day.locator('option'), `February ${year}`).toHaveCount(daysInFebruary + 1);
      }
    });
  });

  test.describe('failure', () => {
    test.use({ persona: 'alice', allowedConsoleErrors: [/orders\/history/] });

    test('a failed load shows an error instead of an empty table', async ({ page }) => {
      await page.route('**/api/order/orders/history**', route =>
        route.fulfill({ status: 500, json: { message: 'Order history is unavailable' } }));
      const history = new OrderHistoryPage(page);
      await history.goto();

      await expect(history.error).toHaveText('Order history is unavailable');
      await expect(history.empty).toBeHidden();
    });
  });
});
