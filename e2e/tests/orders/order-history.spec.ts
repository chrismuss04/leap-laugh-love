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
      await expect(history.rows.locator('td:nth-child(2)')).toHaveText(content.map(o => o.symbol));

      const rejected = history.rows.filter({ has: page.getByRole('cell', { name: 'REJECTED', exact: true }) }).first();
      await rejected.click();
      await expect(history.fills).toContainText('No fills for this order');
    });
  });

  test.describe('pagination', () => {
    // 25 seeded orders, one per hour back from when the database was seeded.
    test.use({ persona: 'history' });

    test('pages through 20 orders at a time', async ({ page }) => {
      const history = new OrderHistoryPage(page);
      await history.goto();
      await expect(history.rows).toHaveCount(20);
      await expect(history.pageInfo).toHaveText('Page 1 of 2');

      await history.next.click();
      await history.expectSettled();
      await expect(history.pageInfo).toHaveText('Page 2 of 2');
      await expect(history.rows).toHaveCount(5);
    });
  });
});
