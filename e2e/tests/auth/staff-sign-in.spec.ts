import { test, expect } from '../../fixtures/test';
import { PASSWORD, staff } from '../../data/users';
import { SignInPage } from '../../pages/sign-in.page';

// Activity Reporting: staff sign in on the client sign-in screen, land on the reporting
// dashboard, and can't reach trading - in the UI or through the APIs.
test.describe('Staff sign in', () => {
  for (const member of [staff.analyst, staff.tradingOps]) {
    test(`${member.roleLabel} lands on reporting, never on trading`, async ({ page }) => {
      const trading: string[] = [];
      page.on('request', request => {
        // Activity reports (/api/order/reports/) are the analyst's own, not trading.
        if (/\/api\/(account|order(?!\/reports\/)|marketdata)\/|\/api\/iam\/v1\/clients\/me/.test(request.url())) {
          trading.push(request.url());
        }
      });
      const signIn = new SignInPage(page);
      await signIn.goto();
      await signIn.signIn(member.email, PASSWORD);

      await expect(page).toHaveURL(/\/reporting$/);
      await expect(page.getByRole('heading', { name: 'Reporting dashboard' })).toBeVisible();
      await expect(page.getByText(member.email)).toBeVisible();
      await expect(page.getByText(member.roleLabel, { exact: true })).toBeVisible();

      for (const path of ['/dashboard', '/orders', '/holdings', '/accounts']) {
        await page.goto(path);
        await expect(page).toHaveURL(/\/reporting$/);
      }
      // The trading pages never loaded, not even for a moment while redirecting.
      expect(trading).toEqual([]);

      await page.getByRole('button', { name: 'Sign out' }).click();
      await expect(page.getByRole('heading', { name: 'Welcome back' })).toBeVisible();
    });
  }

  test('an analyst token is refused by every trading API', async ({ request }) => {
    const login = await request.post('/api/iam/auth/login', {
      data: { email: staff.analyst.email, password: PASSWORD }
    });
    expect(login.ok()).toBeTruthy();
    const { accessToken, role } = await login.json();
    expect(role).toBe('COMMERCIAL_ANALYST');
    const headers = { Authorization: `Bearer ${accessToken}` };

    for (const path of ['/api/iam/v1/clients/me', '/api/account/accounts', '/api/order/orders/history',
      '/api/marketdata/prices']) {
      expect((await request.get(path, { headers })).status(), path).toBe(403);
    }
    expect((await request.post('/api/order/orders', { headers, data: {} })).status()).toBe(403);
    // ...but it is a live session, which signing out ends.
    expect((await request.post('/api/iam/session/activity', { headers })).status()).toBe(204);
    expect((await request.post('/api/iam/session/logout', { headers })).status()).toBe(204);
    expect((await request.post('/api/iam/session/activity', { headers })).status()).toBe(401);
  });
});
