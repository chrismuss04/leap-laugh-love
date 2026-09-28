import { test, expect } from '../../fixtures/test';
import { DashboardPage } from '../../pages/dashboard.page';
import { Shell } from '../../pages/shell';

test.describe('Session', () => {
  test.describe('signed in', () => {
    test.use({ persona: 'alice' });

    test('logging out returns to sign in and forgets the session @smoke', async ({ page }) => {
      await new DashboardPage(page).goto();
      await new Shell(page).signOut();

      await expect(page.getByRole('heading', { name: 'Welcome back' })).toBeVisible();
      await page.reload();
      await expect(page.getByRole('heading', { name: 'Welcome back' })).toBeVisible();
    });
  });

  test('a rejected token signs the client out', async ({ page }) => {
    // What an expired JWT looks like to the app: present, but refused by every service.
    await page.context().addInitScript(() => {
      if (!sessionStorage.getItem('e2e-token-planted')) {
        sessionStorage.setItem('e2e-token-planted', '1');
        localStorage.setItem('auth_token', 'eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ4In0.invalid-signature');
      }
    });
    await page.goto('/dashboard');

    await expect(page.getByRole('heading', { name: 'Welcome back' })).toBeVisible();
    expect(await page.evaluate(() => localStorage.getItem('auth_token'))).toBeNull();
  });
});
