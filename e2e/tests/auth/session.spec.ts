import { test, expect } from '../../fixtures/test';
import { PASSWORD, personas } from '../../data/users';
import { SignInPage } from '../../pages/sign-in.page';
import { Shell } from '../../pages/shell';
import { DashboardPage } from '../../pages/dashboard.page';

test.describe('Session', () => {
  test.describe('signed in', () => {
    test.use({ persona: 'alice' });

    test('logging out returns to sign in and forgets the session @smoke', async ({ page }) => {
      await new DashboardPage(page).goto();
      await new Shell(page).signOut();

      await expect(page.getByRole('heading', { name: 'Welcome back' })).toBeVisible();
      expect(await page.evaluate(() => localStorage.getItem('auth_token'))).toBeNull();

      await page.reload();
      await expect(page.getByRole('heading', { name: 'Welcome back' })).toBeVisible();
    });

    test('the session survives a reload', async ({ page }) => {
      const dashboard = new DashboardPage(page);
      await dashboard.goto();
      await page.reload();

      await dashboard.expectLoaded();
      await new Shell(page).expectSignedInAs(personas.alice.fullName);
    });
  });

  test('a rejected token signs the client out', async ({ page, baseURL }) => {
    // What an expired JWT looks like to the app: present, but refused by every service.
    await page.context().addInitScript(() => {
      if (!sessionStorage.getItem('e2e-token-planted')) {
        sessionStorage.setItem('e2e-token-planted', '1');
        localStorage.setItem('auth_token', 'eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ4In0.invalid-signature');
      }
    });
    await page.goto(`${baseURL}/dashboard`);

    await expect(page.getByRole('heading', { name: 'Welcome back' })).toBeVisible();
    expect(await page.evaluate(() => localStorage.getItem('auth_token'))).toBeNull();
  });

  test('a deep link while signed out shows sign in, then the dashboard after signing in', async ({ page }) => {
    await page.goto('/orders');
    const signIn = new SignInPage(page);
    await expect(page.getByRole('heading', { name: 'Welcome back' })).toBeVisible();

    await signIn.signIn(personas.alice.email, PASSWORD);
    // There is no return-to-URL today: sign-in always lands on the dashboard.
    await expect(page).toHaveURL(/\/dashboard$/);
  });
});
