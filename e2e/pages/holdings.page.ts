import { expect, Locator, Page } from '@playwright/test';

export class HoldingsPage {
  readonly accounts: Locator;
  readonly empty: Locator;
  readonly error: Locator;

  constructor(readonly page: Page) {
    this.accounts = page.getByTestId('holdings-account');
    this.empty = page.getByTestId('no-holdings');
    this.error = page.locator('.error-message');
  }

  async goto(): Promise<void> {
    await this.page.goto('/holdings');
    await expect(this.page.getByRole('heading', { name: /Current Holdings/ })).toBeVisible();
    await expect(this.page.locator('.loading')).toBeHidden();
    await expect(this.accounts.or(this.empty).or(this.error).first()).toBeVisible();
  }

  account(accountNumber: string): Locator {
    return this.accounts.filter({ has: this.page.locator('.account-number', { hasText: accountNumber }) });
  }

  /** Row for one symbol within one account's table. */
  position(accountNumber: string, symbol: string): Locator {
    return this.account(accountNumber).getByRole('row').filter({
      has: this.page.getByRole('cell', { name: symbol, exact: true })
    });
  }
}
