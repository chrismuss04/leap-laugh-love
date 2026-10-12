import { test, expect } from '../../fixtures/test';
import { DashboardPage } from '../../pages/dashboard.page';
import { Shell } from '../../pages/shell';

test.use({ persona: 'alice' });

// Verify an idle browser returns to sign-in, clears its token, and requests server logout.
test('idle session signs out', async ({ page }) => {
  await page.clock.install();
  await new DashboardPage(page).goto();
  const logout = page.waitForResponse(response =>
    response.url().endsWith('/api/iam/session/logout') && response.request().method() === 'POST');

  // Advance browser time only; backend revocation is checked separately by the API suite.
  await page.clock.fastForward(10 * 60 * 1000);

  await expect(page.getByRole('heading', { name: 'Welcome back' })).toBeVisible();
  await expect(page.getByRole('status')).toContainText('10 minutes of inactivity');
  expect(await page.evaluate(() => localStorage.getItem('auth_token'))).toBeNull();
  expect((await logout).status()).toBe(204);
  await page.reload();
  await expect(page.getByRole('heading', { name: 'Welcome back' })).toBeVisible();
});

// Verify logging out in one tab removes authenticated content from another open tab.
test('logout reaches other tabs', async ({ page, context }) => {
  await new DashboardPage(page).goto();
  const otherTab = await context.newPage();
  try {
    await new DashboardPage(otherTab).goto();
    await new Shell(page).signOut();

    await expect(page.getByRole('heading', { name: 'Welcome back' })).toBeVisible();
    await expect(otherTab.getByRole('heading', { name: 'Welcome back' })).toBeVisible();
    await expect(otherTab.getByRole('status')).toContainText('signed out in another tab');
    await expect(otherTab.getByRole('navigation', { name: 'Main' })).toHaveCount(0);
    expect(await otherTab.evaluate(() => localStorage.getItem('auth_token'))).toBeNull();
  } finally {
    await otherTab.close();
  }
});
