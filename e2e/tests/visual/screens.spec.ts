import { test, expect } from '../../fixtures/test';
import { DashboardPage } from '../../pages/dashboard.page';
import { RegistrationPage } from '../../pages/registration.page';
import { SignInPage } from '../../pages/sign-in.page';

// Pixel baselines, Chromium only (see playwright.config.ts). Baselines are OS-specific, so the
// committed ones come from the Linux CI image - regenerate with `npm run test:visual:update`
// (which runs in that image) rather than on a desktop.
test.describe('Visual @visual', () => {
  test('sign in', async ({ page }) => {
    await new SignInPage(page).goto();
    await expect(page).toHaveScreenshot('sign-in.png', { fullPage: true });
  });

  test('create account', async ({ page }) => {
    await new RegistrationPage(page).goto();
    await expect(page).toHaveScreenshot('create-account.png', { fullPage: true });
  });

  test.describe('signed in', () => {
    test.use({ persona: 'alice' });

    test('dashboard layout', async ({ page }) => {
      const dashboard = new DashboardPage(page);
      await dashboard.goto();
      await expect(dashboard.chart).toBeVisible();
      // Everything driven by the simulated market changes every second; compare the layout around it.
      await expect(page).toHaveScreenshot('dashboard.png', {
        mask: [
          dashboard.tickerStrip, dashboard.headline, page.locator('.hero .change'), page.locator('.chart-area'),
          page.locator('.stats .stat-value'), dashboard.positions.locator('.rows'), dashboard.activity.locator('.rows'),
          dashboard.trade.root, page.locator('.live-status')
        ],
        animations: 'disabled',
        maxDiffPixelRatio: 0.01
      });
    });
  });
});
