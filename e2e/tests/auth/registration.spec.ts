import { test, expect } from '../../fixtures/test';
import { Registration, uniqueRegistration } from '../../data/factories';
import { Api } from '../../fixtures/api';
import { APPLICATION_SUBJECT, DETAILS_REUSED_SUBJECT, confirmationToken, emailTo } from '../../fixtures/mailbox';
import { money } from '../../pages/format';
import { RegistrationPage } from '../../pages/registration.page';
import { SignInPage } from '../../pages/sign-in.page';
import { DashboardPage } from '../../pages/dashboard.page';

// Field-by-field validation belongs in the component's unit tests; these cover what only the
// whole stack can: the application reaching iam-app, the emailed link opening the account, and
// iam-app's uniqueness rules - which the page must never give away, only the emails.
test.describe('Create account', () => {
  let form: RegistrationPage;

  test.beforeEach(async ({ page }) => {
    form = new RegistrationPage(page);
    await form.goto();
  });

  /** What the page says after any accepted application, whatever iam-app did with it. */
  const received = (email: string) =>
    `Application received! We've emailed ${email} with the next step. Your account is not open until you follow it.`;

  test('a complete application registers once its email is confirmed, and the new client can sign in @smoke', async ({ page, tokenFor }) => {
    // Confirming blocks while iam-app opens and funds the account in account-app, and the CI VM
    // runs the whole stack on shared CPUs, so this test gets more room than the suite default.
    test.setTimeout(120_000);
    const slow = { timeout: 30_000 };

    // Not a round number, so a hardcoded amount or a rounded one can't satisfy the assertions below.
    const initialDeposit = 12_500.75;
    const user = uniqueRegistration({ initialDeposit });
    await form.fill(user);
    await form.submit.click();

    await expect(form.success).toHaveText(received(user.email), slow);

    // Nothing is open yet: the application becomes a client when the emailed link is followed.
    expect((await new Api(page.request).loginRaw(user.email, user.password)).status()).toBe(401);
    await page.goto(`/verify-email?token=${await confirmationToken(page.request, user.email)}`);
    await expect(page.getByRole('status')).toHaveText('Your email is confirmed and your account is open. You can now sign in.', slow);
    await page.getByRole('link', { name: 'Back to sign in' }).click();
    await expect(page.getByRole('heading', { name: 'Welcome back' })).toBeVisible(slow);

    // Confirming opens the first account and books the initial deposit into it before it responds,
    // so the backend is checked first: a failure here names the step that broke (no account, or an
    // account with no deposit) instead of surfacing later as a bare "$0.00" on the dashboard.
    // Polled, because a deposit that outlasted iam-app's call timeout can still commit just after.
    const api = new Api(page.request).as(await tokenFor(user.email));
    await expect
      .poll(async () => (await api.accounts()).length, {
        ...slow,
        message:
          'Registration succeeded but no account was opened. iam-app could not create it in account-app; ' +
          'see its "could not open their first account" log line.',
      })
      .toBe(1);
    const accounts = await api.accounts();
    expect(accounts[0].accountNumber).toMatch(/^ACC-[A-Z0-9]{8}$/);
    expect(accounts[0].status).toBe('ACTIVE');
    expect(accounts[0].tradingEnabled).toBe(true);

    await expect
      .poll(async () => (await api.balance()).accounts.find(a => a.accountId === accounts[0].accountId)?.balance, {
        ...slow,
        message:
          `The account ${accounts[0].accountNumber} was opened but the ${money(initialDeposit)} initial deposit ` +
          'was not booked; see the iam-app "initial deposit ... unconfirmed" log line.',
      })
      .toBe(initialDeposit);
    const { accounts: balances } = await api.balance();
    expect(balances, 'The balance API should list the new account').toHaveLength(1);
    expect(balances[0].accountId).toBe(accounts[0].accountId);
    expect(balances[0].currency).toBe('USD');

    await new SignInPage(page).signIn(user.email, user.password);
    await expect(page).toHaveURL(/\/dashboard$/, slow);

    // The same cash shows as buying power on the dashboard, with no holdings yet.
    const dashboard = new DashboardPage(page);
    await expect(dashboard.buyingPower).toContainText(money(initialDeposit), slow);
    await expect(dashboard.positions).toContainText("You don't own any investments yet", slow);
  });

  test('a confirmation link works once', async ({ page, api }) => {
    const user = uniqueRegistration();
    await api.registerRaw(user);
    const token = await confirmationToken(page.request, user.email);
    expect((await api.verifyRegistrationRaw(token)).status()).toBe(204);

    await page.goto(`/verify-email?token=${token}`);

    await expect(page.getByRole('alert')).toContainText('This confirmation link has expired or has already been used.');
  });

  test('an email that is already registered is answered like a new one, and its owner is emailed', async ({ page, api }) => {
    const existing = uniqueRegistration();
    await api.register(existing);

    await form.fill(uniqueRegistration({ email: existing.email }));
    await form.submit.click();

    await expect(form.success).toHaveText(received(existing.email));
    await expect(form.error).toBeHidden();
    expect(await emailTo(page.request, existing.email, APPLICATION_SUBJECT)).toContain('already registered');
  });

  for (const [detail, named, reuse] of [
    ['SSN', 'the Social Security Number on your account', (existing: Registration) => ({ ssnDigits: existing.ssnDigits })],
    ['phone number', 'the phone number on your account', (existing: Registration) => ({ phoneDigits: existing.phoneDigits })]
  ] as const) {
    test(`a ${detail} that is already registered is answered like a new one, and opens nothing`, async ({ page, api }) => {
      const existing = uniqueRegistration();
      await api.register(existing);

      const applicant = uniqueRegistration(reuse(existing));
      await form.fill(applicant);
      await form.submit.click();

      await expect(form.success).toHaveText(received(applicant.email));
      await expect(form.error).toBeHidden();
      // The applicant is told it didn't go through, but not why; the client whose detail it was
      // is warned, and told which detail.
      const refusal = await emailTo(page.request, applicant.email, APPLICATION_SUBJECT);
      expect(refusal).toContain('could not open');
      expect(refusal).not.toContain(named);
      expect(await emailTo(page.request, existing.email, DETAILS_REUSED_SUBJECT)).toContain(named);
      expect((await api.loginRaw(applicant.email, applicant.password)).status()).toBe(401);
    });
  }
});
