import { expect, Locator, Page } from '@playwright/test';
import { Registration } from '../data/factories';

/** The Create account tab. */
export class RegistrationPage {
  readonly form: Locator;
  readonly firstName: Locator;
  readonly lastName: Locator;
  readonly dateOfBirth: Locator;
  readonly email: Locator;
  readonly phone: Locator;
  readonly password: Locator;
  readonly confirmPassword: Locator;
  readonly streetAddress: Locator;
  readonly city: Locator;
  readonly stateRegion: Locator;
  readonly postalCode: Locator;
  readonly country: Locator;
  readonly ssn: Locator;
  readonly initialDeposit: Locator;
  readonly terms: Locator;
  readonly submit: Locator;
  readonly error: Locator;
  readonly success: Locator;

  constructor(readonly page: Page) {
    this.form = page.locator('form.registration-form');
    this.firstName = this.form.getByLabel('First Name');
    this.lastName = this.form.getByLabel('Last Name');
    // The date field sits under a section heading rather than a <label>.
    this.dateOfBirth = this.form.locator('#dateOfBirth');
    this.email = this.form.getByLabel('Email Address');
    this.phone = this.form.getByLabel('Phone Number');
    this.password = this.form.getByLabel('Password', { exact: true });
    this.confirmPassword = this.form.getByLabel('Confirm Password');
    this.streetAddress = this.form.getByLabel('Street Address');
    this.city = this.form.getByLabel('City');
    this.stateRegion = this.form.getByLabel('State / Region');
    this.postalCode = this.form.getByLabel('Postal Code');
    this.country = this.form.getByLabel('Country');
    this.ssn = this.form.getByLabel('SSN / Tax ID');
    this.initialDeposit = this.form.getByLabel('Initial Deposit Amount');
    this.terms = this.form.getByRole('checkbox');
    // Not by name: while sending, the button shows only a spinner and has no accessible name.
    this.submit = this.form.locator('button[type="submit"]');
    this.error = this.form.locator('.error-alert');
    this.success = this.form.locator('.success-alert');
  }

  experience(level: Registration['experience']): Locator {
    return this.form.getByRole('button', { name: level, exact: true });
  }

  async goto(): Promise<void> {
    await this.page.goto('/');
    await this.page.locator('.tabs').getByRole('button', { name: 'Create account' }).click();
    await expect(this.page.getByRole('heading', { name: 'Open your account' })).toBeVisible();
  }

  /** Fills every field. Digits are typed so the phone/SSN auto-formatting runs as it would for a person. */
  async fill(r: Registration): Promise<void> {
    await this.firstName.fill(r.firstName);
    await this.lastName.fill(r.lastName);
    await this.dateOfBirth.fill(r.dateOfBirth);
    await this.email.fill(r.email);
    await this.phone.pressSequentially(r.phoneDigits);
    await this.password.fill(r.password);
    await this.confirmPassword.fill(r.password);
    await this.streetAddress.fill(r.streetAddress);
    await this.city.fill(r.city);
    await this.stateRegion.fill(r.stateRegion);
    await this.postalCode.fill(r.postalCode);
    await this.country.selectOption(r.countryCode);
    await this.ssn.pressSequentially(r.ssnDigits);
    await this.experience(r.experience).click();
    await this.initialDeposit.fill(String(r.initialDeposit));
    await this.terms.check();
  }

  fieldError(field: Locator): Locator {
    return field.locator('xpath=ancestor::div[contains(@class,"form-group")][1]').locator('.error-message');
  }
}
