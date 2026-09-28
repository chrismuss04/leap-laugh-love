import { expect, Locator, Page } from '@playwright/test';

/** The signed-out screen: the Sign in tab and the switch to Create account. */
export class SignInPage {
  readonly form: Locator;
  readonly email: Locator;
  readonly password: Locator;
  readonly rememberMe: Locator;
  readonly submit: Locator;
  readonly showPassword: Locator;
  readonly error: Locator;
  readonly success: Locator;

  constructor(readonly page: Page) {
    this.form = page.locator('form.signin-form');
    this.email = this.form.getByLabel('Email Address');
    this.password = this.form.getByLabel('Password', { exact: true });
    this.rememberMe = this.form.getByRole('checkbox', { name: 'Remember me' });
    // Not by name: while signing in the button shows only a spinner and has no accessible name.
    this.submit = this.form.locator('button[type="submit"]');
    this.showPassword = this.form.getByRole('button', { name: /show password|hide password/i });
    this.error = this.form.locator('.error-alert');
    this.success = this.form.locator('.success-alert');
  }

  tab(name: 'Sign in' | 'Create account'): Locator {
    return this.page.locator('.tabs').getByRole('button', { name, exact: true });
  }

  async goto(): Promise<void> {
    await this.page.goto('/');
    await expect(this.page.getByRole('heading', { name: 'Welcome back' })).toBeVisible();
  }

  async signIn(email: string, password: string): Promise<void> {
    await this.email.fill(email);
    await this.password.fill(password);
    await this.submit.click();
  }

  /** The inline validation message under a field. */
  fieldError(field: Locator): Locator {
    return field.locator('xpath=ancestor::div[contains(@class,"form-group")][1]').locator('.error-message');
  }
}
