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

    await expect(page).toHaveURL(/\/dashboard$/);
    await new Shell(page).expectSignedInAs(personas.alice.fullName);
  });

  test('a wrong password is refused and the form keeps what was typed', async () => {
    await signIn.signIn(personas.alice.email, 'WrongPassword1!');

    await expect(signIn.error).toHaveText('Invalid email or password');
    // A 401 without a token is an answer, not an expired session - the interceptor must not
    // reload the page and wipe the form.
    await expect(signIn.email).toHaveValue(personas.alice.email);
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
});
