import { test, expect } from '../../fixtures/test';
import { Shell } from '../../pages/shell';
import { DashboardPage } from '../../pages/dashboard.page';

test.use({ persona: 'alice' });

test.describe('Shell', () => {
  let shell: Shell;

  test.beforeEach(async ({ page }) => {
    shell = new Shell(page);
    await new DashboardPage(page).goto();
  });

  test('the main nav reaches every page @smoke', async ({ page }) => {
    await shell.link('Orders').click();
    await expect(page.getByRole('heading', { name: /Order History/ })).toBeVisible();

    await shell.link('Holdings').click();
    await expect(page.getByRole('heading', { name: /Current Holdings/ })).toBeVisible();

    await shell.link('Dashboard').click();
    await expect(page).toHaveURL(/\/dashboard$/);
  });

  test('the live indicator connects to the price stream @smoke', async () => {
    await expect(shell.liveStatus).toHaveAttribute('data-status', 'live', { timeout: 20_000 });
  });
});
