import { test, expect } from '../../fixtures/test';
import { PASSWORD, personas } from '../../data/users';
import { uniqueRegistration } from '../../data/factories';
import { SignInPage } from '../../pages/sign-in.page';
import { Shell } from '../../pages/shell';

test.describe('Sign in', () => {
  let signIn: SignInPage;

  test.beforeEach(async ({ page }) => {
    signIn = new SignInPage(page);
    await signIn.goto();
  });

  test('a seeded client signs in and lands on the dashboard @smoke', async ({ page }) => {
    await signIn.signIn(personas.alice.email, PASSWORD);

    // No assertion on the "Sign in successful! Redirecting..." message: storing the token
    // swaps the sign-in screen for the app straight away, so it is never on screen.
    await expect(page).toHaveURL(/\/dashboard$/);
    const shell = new Shell(page);
    await shell.expectSignedInAs(personas.alice.fullName);
    await expect(shell.profileButton).toContainText(personas.alice.experience);
    await expect(page.locator('.avatar')).toHaveText('AJ');
  });

  test('pressing Enter in the password field submits', async ({ page }) => {
    await signIn.email.fill(personas.alice.email);
    await signIn.password.fill(PASSWORD);
    await signIn.password.press('Enter');

    await expect(page).toHaveURL(/\/dashboard$/);
  });

  test('a wrong password shows the server message and keeps the form filled', async () => {
    await signIn.signIn(personas.alice.email, 'WrongPassword1!');

    await expect(signIn.error).toHaveText('Invalid email or password');
    // A 401 without a token is an answer, not an expired session - the interceptor must not
    // reload the page and wipe what was typed.
    await expect(signIn.email).toHaveValue(personas.alice.email);
    await expect(signIn.password).toHaveValue('WrongPassword1!');
  });

  test('an unknown email gets the same message as a wrong password', async () => {
    await signIn.signIn('nobody.here@leap.test', PASSWORD);

    await expect(signIn.error).toHaveText('Invalid email or password');
  });

  test('a locked account is refused with an explanation', async () => {
    await signIn.signIn(personas.grace.email, PASSWORD);

    await expect(signIn.error).toHaveText('Account is locked due to too many failed login attempts');
  });

  test('three wrong passwords lock the account, after which the right one is refused too', async ({ api }) => {
    // Lockout is permanent, so this must never touch a shared persona.
    const user = uniqueRegistration();
    await api.register(user);

    for (let attempt = 1; attempt <= 2; attempt++) {
      await signIn.signIn(user.email, 'WrongPassword1!');
      await expect(signIn.error).toHaveText('Invalid email or password');
    }
    await signIn.signIn(user.email, 'WrongPassword1!');
    await expect(signIn.error).toHaveText('Account is locked due to too many failed login attempts');

    await signIn.signIn(user.email, user.password);
    await expect(signIn.error).toHaveText('Account is locked due to too many failed login attempts');
  });

  test.describe('client-side validation', () => {
    test('submitting an empty form asks for every field', async () => {
      await signIn.submit.click();

      await expect(signIn.error).toHaveText('Please fill in all required fields correctly.');
    });

    test('email and password rules show under their fields once touched', async () => {
      await signIn.email.fill('not-an-email');
      await signIn.password.fill('short');
      await signIn.password.blur();

      await expect(signIn.fieldError(signIn.email)).toHaveText('Please enter a valid email address');
      await expect(signIn.fieldError(signIn.password)).toHaveText('Password must be at least 8 characters');

      await signIn.email.fill('');
      await signIn.password.fill('');
      await expect(signIn.fieldError(signIn.email)).toHaveText('Email is required');
      await expect(signIn.fieldError(signIn.password)).toHaveText('Password is required');
    });
  });

  test('the eye button reveals and hides the password', async () => {
    await signIn.password.fill(PASSWORD);
    await expect(signIn.password).toHaveAttribute('type', 'password');

    await signIn.showPassword.click();
    await expect(signIn.password).toHaveAttribute('type', 'text');
    await expect(signIn.showPassword).toHaveAccessibleName('Hide password');

    await signIn.showPassword.click();
    await expect(signIn.password).toHaveAttribute('type', 'password');
  });

  test('the button is disabled while the request is in flight', async ({ page }) => {
    let release!: () => void;
    const held = new Promise<void>(resolve => (release = resolve));
    await page.route('**/api/iam/auth/login', async route => {
      await held;
      await route.continue();
    });

    await signIn.signIn(personas.alice.email, PASSWORD);
    await expect(signIn.submit).toBeDisabled();
    await expect(signIn.submit.locator('.loading-spinner')).toBeVisible();

    release();
    await expect(page).toHaveURL(/\/dashboard$/);
  });

  test('"Remember me" is recorded', async ({ page }) => {
    await signIn.rememberMe.check();
    await signIn.signIn(personas.alice.email, PASSWORD);

    await expect(page).toHaveURL(/\/dashboard$/);
    // Polled: sign-in follows up with a full reload a second later, and a read that lands
    // mid-navigation has no document to ask.
    await expect.poll(() => page.evaluate(() => localStorage.getItem('rememberMe')).catch(() => null)).toBe('true');
  });

  test('the tabs and footer link switch between sign in and create account', async ({ page }) => {
    await signIn.tab('Create account').click();
    await expect(page.getByRole('heading', { name: 'Open your account' })).toBeVisible();

    // These footer anchors have no href, so they have no link role to find them by.
    await page.locator('.auth-footer-link').getByText('Sign in', { exact: true }).click();
    await expect(page.getByRole('heading', { name: 'Welcome back' })).toBeVisible();

    await page.locator('.auth-footer-link').getByText('Sign up', { exact: true }).click();
    await expect(page.getByRole('heading', { name: 'Open your account' })).toBeVisible();

    await signIn.tab('Sign in').click();
    await expect(page.getByRole('heading', { name: 'Welcome back' })).toBeVisible();
  });
});
