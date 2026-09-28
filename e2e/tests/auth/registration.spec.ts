import { test, expect } from '../../fixtures/test';
import { dateOfBirthForAge, formatPhone, formatSsn, uniqueRegistration } from '../../data/factories';
import { RegistrationPage } from '../../pages/registration.page';
import { SignInPage } from '../../pages/sign-in.page';
import { DashboardPage } from '../../pages/dashboard.page';

test.describe('Create account', () => {
  let form: RegistrationPage;

  test.beforeEach(async ({ page }) => {
    form = new RegistrationPage(page);
    await form.goto();
  });

  test('a complete application registers, returns to sign in, and the new client can sign in @smoke', async ({ page }) => {
    const user = uniqueRegistration();
    await form.fill(user);
    await form.submit.click();

    await expect(form.success).toHaveText('Application submitted! Redirecting to sign in...');
    await expect(page.getByRole('heading', { name: 'Welcome back' })).toBeVisible();

    await new SignInPage(page).signIn(user.email, user.password);
    await expect(page).toHaveURL(/\/dashboard$/);

    // Registration creates the client only; a trading account is opened separately. Until then
    // the dashboard should say so rather than error.
    const dashboard = new DashboardPage(page);
    await expect(dashboard.positions).toContainText("You don't own any investments yet");
    await expect(dashboard.activity).toContainText('No orders yet');
  });

  test('phone and SSN format themselves as they are typed', async () => {
    await form.phone.pressSequentially('5551234567');
    await expect(form.phone).toHaveValue('(555) 123-4567');

    await form.ssn.pressSequentially('123456789');
    await expect(form.ssn).toHaveValue('123-45-6789');

    // Non-digits are dropped, and input stops at a full number.
    await form.ssn.fill('');
    await form.ssn.pressSequentially('12a3-45x67899999');
    await expect(form.ssn).toHaveValue('123-45-6789');
  });

  test('submitting an empty form flags every required field', async () => {
    await form.submit.click();

    await expect(form.error).toHaveText('Please fill in all required fields correctly.');
    for (const field of [form.firstName, form.lastName, form.dateOfBirth, form.email, form.phone, form.password,
      form.confirmPassword, form.streetAddress, form.city, form.stateRegion, form.postalCode, form.country, form.ssn,
      form.initialDeposit]) {
      await expect(form.fieldError(field), await field.getAttribute('id') ?? '').toHaveText('This field is required');
    }
    await expect(form.form.getByText('You must accept the terms to continue')).toBeVisible();
  });

  test.describe('field rules', () => {
    test('applicants must be at least 21', async () => {
      await form.dateOfBirth.fill(dateOfBirthForAge(21, true));
      await form.dateOfBirth.blur();
      await expect(form.fieldError(form.dateOfBirth)).toHaveText('You must be at least 21 years old');

      await form.dateOfBirth.fill(dateOfBirthForAge(21));
      await expect(form.fieldError(form.dateOfBirth)).toBeHidden();
    });

    test('email must be valid', async () => {
      await form.email.fill('not-an-email');
      await form.email.blur();
      await expect(form.fieldError(form.email)).toHaveText('Please enter a valid email address');
    });

    test('an incomplete phone number is rejected', async () => {
      await form.phone.pressSequentially('55512');
      await form.phone.blur();
      await expect(form.fieldError(form.phone)).toHaveText('Please enter a valid phone number');
    });

    test('an incomplete SSN is rejected', async () => {
      await form.ssn.pressSequentially('12345');
      await form.ssn.blur();
      await expect(form.fieldError(form.ssn)).toHaveText('SSN / Tax ID must be in format XXX-XX-XXXX');
    });

    test('password must be 8+ characters and confirmed', async () => {
      await form.password.fill('short');
      await form.password.blur();
      await expect(form.fieldError(form.password)).toHaveText('Password must be at least 8 characters');

      await form.password.fill('Password123!');
      await form.confirmPassword.fill('Password123?');
      await form.confirmPassword.blur();
      await expect(form.fieldError(form.confirmPassword)).toHaveText('Passwords do not match');

      await form.confirmPassword.fill('Password123!');
      await expect(form.fieldError(form.confirmPassword)).toBeHidden();
    });

    test('the initial deposit must be at least $5,000', async () => {
      await form.initialDeposit.fill('4999.99');
      await form.initialDeposit.blur();
      await expect(form.fieldError(form.initialDeposit)).toHaveText('Minimum initial deposit is $5,000');

      await form.initialDeposit.fill('5000');
      await expect(form.fieldError(form.initialDeposit)).toBeHidden();
    });

    test('experience level is a single choice', async () => {
      await form.experience('Beginner').click();
      await expect(form.experience('Beginner')).toHaveClass(/selected/);

      await form.experience('Advanced').click();
      await expect(form.experience('Advanced')).toHaveClass(/selected/);
      await expect(form.experience('Beginner')).not.toHaveClass(/selected/);
    });

    test('terms must be accepted', async () => {
      const user = uniqueRegistration();
      await form.fill(user);
      await form.terms.uncheck();
      await form.submit.click();

      await expect(form.form.getByText('You must accept the terms to continue')).toBeVisible();
      await expect(form.error).toHaveText('Please fill in all required fields correctly.');
    });
  });

  test.describe('server-side rules', () => {
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

    test('the submit button is disabled while the application is sent', async ({ page }) => {
      let release!: () => void;
      const held = new Promise<void>(resolve => (release = resolve));
      await page.route('**/api/iam/v1/clients/register', async route => {
        await held;
        await route.continue();
      });

      await form.fill(uniqueRegistration());
      await form.submit.click();
      await expect(form.submit).toBeDisabled();

      release();
      await expect(form.success).toBeVisible();
    });
  });

  test('the request carries the formatted phone, SSN and API experience code', async ({ page }) => {
    const user = uniqueRegistration({ experience: 'Beginner' });
    await form.fill(user);
    const [request] = await Promise.all([
      page.waitForRequest('**/api/iam/v1/clients/register'),
      form.submit.click()
    ]);

    expect(request.postDataJSON()).toMatchObject({
      email: user.email,
      phone: formatPhone(user.phoneDigits),
      ssn: formatSsn(user.ssnDigits),
      fullName: `${user.firstName} ${user.lastName}`,
      dateOfBirth: user.dateOfBirth,
      countryCode: 'US',
      experienceLevel: 'NOVICE'
    });
  });
});
