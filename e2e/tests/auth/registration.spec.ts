import { test, expect } from '../../fixtures/test';
import { uniqueRegistration } from '../../data/factories';
import { Api } from '../../fixtures/api';
import { money } from '../../pages/format';
import { RegistrationPage } from '../../pages/registration.page';
import { SignInPage } from '../../pages/sign-in.page';
import { DashboardPage } from '../../pages/dashboard.page';

// Field-by-field validation belongs in the component's unit tests; these cover what only the
// whole stack can: the application reaching iam-app, and iam-app's uniqueness rules.
test.describe('Create account', () => {
  let form: RegistrationPage;

  test.beforeEach(async ({ page }) => {
    form = new RegistrationPage(page);
    await form.goto();
  });

  test('a complete application registers and the new client can sign in @smoke', async ({ page, tokenFor }) => {
    // Not a round number, so a hardcoded amount or a rounded one can't satisfy the assertions below.
    const initialDeposit = 12_500.75;
    const user = uniqueRegistration({ initialDeposit });
    await form.fill(user);
    await form.submit.click();

    await expect(form.success).toHaveText('Application submitted! Redirecting to sign in...');
    await expect(page.getByRole('heading', { name: 'Welcome back' })).toBeVisible();

    await new SignInPage(page).signIn(user.email, user.password);
    await expect(page).toHaveURL(/\/dashboard$/);

    // Registration opens the first account and books the initial deposit into it, so the
    // dashboard shows that cash as buying power and no holdings yet.
    const dashboard = new DashboardPage(page);
    await expect(dashboard.buyingPower).toContainText(money(initialDeposit));
    await expect(dashboard.positions).toContainText("You don't own any investments yet");

    // The same, read from the backend: exactly one open, tradable account holding the deposit.
    const api = new Api(page.request).as(await tokenFor(user.email));
    const accounts = await api.accounts();
    expect(accounts).toHaveLength(1);
    expect(accounts[0].accountNumber).toMatch(/^ACC-[A-Z0-9]{8}$/);
    expect(accounts[0].status).toBe('ACTIVE');
    expect(accounts[0].tradingEnabled).toBe(true);

    const { accounts: balances } = await api.balance();
    expect(balances).toHaveLength(1);
    expect(balances[0]).toMatchObject({ accountId: accounts[0].accountId, currency: 'USD', balance: initialDeposit });
  });

  test('an email that is already registered is refused', async ({ api }) => {
    const existing = uniqueRegistration();
    await api.register(existing);

    await form.fill(uniqueRegistration({ email: existing.email }));
    await form.submit.click();

    await expect(form.error).toHaveText('email already registered');
  });

  test('an SSN that is already registered is refused', async ({ api }) => {
    const existing = uniqueRegistration();
    await api.register(existing);

    await form.fill(uniqueRegistration({ ssnDigits: existing.ssnDigits }));
    await form.submit.click();

    await expect(form.error).toHaveText('ssn already registered');
  });
});
