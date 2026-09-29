import { test, expect } from '../../fixtures/test';
import { uniqueRegistration } from '../../data/factories';
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

  test('a complete application registers and the new client can sign in @smoke', async ({ page }) => {
    const user = uniqueRegistration();
    await form.fill(user);
    await form.submit.click();

    await expect(form.success).toHaveText('Application submitted! Redirecting to sign in...');
    await expect(page.getByRole('heading', { name: 'Welcome back' })).toBeVisible();

    await new SignInPage(page).signIn(user.email, user.password);
    await expect(page).toHaveURL(/\/dashboard$/);
    // Registration creates the client only; the dashboard should say there's nothing yet, not error.
    await expect(new DashboardPage(page).positions).toContainText("You don't own any investments yet");
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
