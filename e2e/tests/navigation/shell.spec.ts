import { test, expect } from '../../fixtures/test';
import { personas } from '../../data/users';
import { Shell } from '../../pages/shell';
import { DashboardPage } from '../../pages/dashboard.page';

test.use({ persona: 'alice' });

test.describe('Shell and navigation', () => {
  let shell: Shell;

  test.beforeEach(async ({ page }) => {
    shell = new Shell(page);
    await new DashboardPage(page).goto();
  });

  test('the main nav moves between pages and marks the current one @smoke', async ({ page }) => {
    await expect(shell.link('Dashboard')).toHaveClass(/active/);

    await shell.link('Orders').click();
    await expect(page).toHaveURL(/\/orders$/);
    await expect(page.getByRole('heading', { name: /Order History/ })).toBeVisible();
    await expect(shell.link('Orders')).toHaveClass(/active/);
    await expect(shell.link('Dashboard')).not.toHaveClass(/active/);

    await shell.link('Holdings').click();
    await expect(page).toHaveURL(/\/holdings$/);
    await expect(page.getByRole('heading', { name: /Current Holdings/ })).toBeVisible();

    await page.getByRole('link', { name: 'Leapfolio home' }).click();
    await expect(page).toHaveURL(/\/dashboard$/);
  });

  test('browser back and forward follow in-app navigation', async ({ page }) => {
    await shell.link('Orders').click();
    await shell.link('Holdings').click();

    await page.goBack();
    await expect(page).toHaveURL(/\/orders$/);
    await page.goBack();
    await expect(page).toHaveURL(/\/dashboard$/);
    await page.goForward();
    await expect(page).toHaveURL(/\/orders$/);
  });

  test('an unknown route falls back to the dashboard', async ({ page }) => {
    await page.goto('/no-such-page');
    await expect(page).toHaveURL(/\/dashboard$/);
  });

  test('the profile menu shows who is signed in and closes on Escape', async () => {
    await shell.expectSignedInAs(personas.alice.fullName);
    await expect(shell.profileButton).toContainText(personas.alice.experience);

    await shell.profileButton.click();
    await expect(shell.profileButton).toHaveAttribute('aria-expanded', 'true');
    await expect(shell.menu).toContainText(personas.alice.fullName);
    await expect(shell.menu).toContainText(personas.alice.email);

    // Escape is handled inside the profile widget, so press it where keyboard focus would be.
    // (Pressing on the page would fail in WebKit, where clicking a button doesn't focus it.)
    await shell.profileButton.press('Escape');
    await expect(shell.menu).toBeHidden();

    await expect(shell.profileButton).toHaveAttribute('aria-expanded', 'false');
  });

  test('the profile menu closes when clicking elsewhere on the page', async ({ page }) => {
    // Known bug: .menu-backdrop is position: fixed inside the header, and the header's
    // backdrop-filter makes it the backdrop's containing block - so the backdrop covers the
    // header only, and a click on the page below leaves the menu open. Remove test.fail once fixed.
    test.fail(true, 'profile menu backdrop only covers the header');
    await shell.profileButton.click();
    await expect(shell.menu).toBeVisible();

    await page.getByRole('region', { name: 'Positions' }).click({ position: { x: 5, y: 5 } });
    await expect(shell.menu).toBeHidden({ timeout: 3_000 });
  });

  test('the live indicator connects to the price stream @smoke', async () => {
    await expect(shell.liveStatus).toHaveAttribute('data-status', 'live', { timeout: 20_000 });
    await expect(shell.liveStatus).toHaveText('Live');
  });
});
