import { expect, Locator, Page } from '@playwright/test';

export class OrderHistoryPage {
  readonly rows: Locator;
  readonly fills: Locator;
  readonly year: Locator;
  readonly month: Locator;
  readonly day: Locator;
  readonly clear: Locator;
  readonly previous: Locator;
  readonly next: Locator;
  readonly pageInfo: Locator;
  readonly empty: Locator;
  readonly error: Locator;

  constructor(readonly page: Page) {
    this.rows = page.getByTestId('order-row');
    this.fills = page.getByTestId('order-fills');
    this.year = page.getByLabel('Year');
    this.month = page.getByLabel('Month');
    this.day = page.getByLabel('Day');
    this.clear = page.getByRole('button', { name: 'Clear' });
    this.previous = page.getByRole('button', { name: /Previous/ });
    this.next = page.getByRole('button', { name: /Next/ });
    this.pageInfo = page.locator('.page-info');
    this.empty = page.locator('.no-orders');
    this.error = page.locator('.error-message');
  }

  async goto(): Promise<void> {
    await this.page.goto('/orders');
    await expect(this.page.getByRole('heading', { name: /Order History/ })).toBeVisible();
    await this.expectSettled();
  }

  /** The table, the empty message or the error is showing - not the loading text. */
  async expectSettled(): Promise<void> {
    await expect(this.page.locator('.loading')).toBeHidden();
    await expect(this.rows.first().or(this.empty).or(this.error)).toBeVisible();
  }

  /** The cells of a row, keyed by column. */
  async row(index: number): Promise<{ symbol: string; side: string; quantity: string; status: string; fills: string }> {
    const cells = await this.rows.nth(index).getByRole('cell').allTextContents();
    const [, symbol, side, quantity, status, , , fills] = cells.map(c => c.trim());
    return { symbol, side, quantity, status, fills };
  }
}
