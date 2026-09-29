import { expect, Locator, Page } from '@playwright/test';

/** The buy/sell ticket in the dashboard's right rail. */
export class TradePanel {
  readonly root: Locator;
  readonly title: Locator;
  readonly livePrice: Locator;
  readonly buySide: Locator;
  readonly sellSide: Locator;
  readonly account: Locator;
  readonly quantity: Locator;
  readonly increment: Locator;
  readonly decrement: Locator;
  readonly estimate: Locator;
  readonly hint: Locator;
  readonly sellAll: Locator;
  readonly reviewButton: Locator;
  readonly reviewText: Locator;
  readonly submitButton: Locator;
  readonly editButton: Locator;
  readonly submitError: Locator;
  readonly result: Locator;
  readonly unconfirmed: Locator;
  readonly done: Locator;

  constructor(readonly page: Page) {
    this.root = page.locator('app-trade-panel');
    this.title = this.root.locator('#trade-title');
    this.livePrice = this.root.locator('.live-price');
    this.buySide = this.root.getByRole('radio', { name: 'Buy' });
    this.sellSide = this.root.getByRole('radio', { name: 'Sell' });
    this.account = this.root.getByLabel('Account');
    this.quantity = this.root.getByLabel('Shares');
    this.increment = this.root.getByRole('button', { name: 'One more share' });
    this.decrement = this.root.getByRole('button', { name: 'One fewer share' });
    this.estimate = this.root.locator('.field-row.total .field-value');
    this.hint = this.root.locator('.hint');
    this.sellAll = this.root.getByRole('button', { name: 'Sell all' });
    this.reviewButton = this.root.getByRole('button', { name: 'Review order' });
    this.reviewText = this.root.locator('.review-text');
    this.submitButton = this.root.locator('.review .cta');
    this.editButton = this.root.getByRole('button', { name: 'Edit order' });
    this.submitError = this.root.locator('.review .error');
    this.result = page.getByTestId('trade-result');
    this.unconfirmed = page.getByTestId('trade-unconfirmed');
    this.done = this.root.getByRole('button', { name: 'Done' });
  }

  /** Waits until the ticket has a live price for its symbol, so estimates can be checked. */
  async expectPriced(): Promise<void> {
    await expect(this.livePrice).toHaveText(/\$/);
  }

  /** Enter shares, review, submit - and wait for the outcome, whatever it is. */
  async place(quantity: number): Promise<void> {
    await this.expectPriced();
    await this.quantity.fill(String(quantity));
    await this.reviewButton.click();
    await this.submitButton.click();
    await expect(this.result.or(this.unconfirmed).or(this.submitError)).toBeVisible();
  }
}
