import { Locator, Page } from '@playwright/test';

/** The dashboard's Open orders card: limit, stop and trailing orders waiting for their price. */
export class OpenOrders {
  readonly card: Locator;
  readonly rows: Locator;
  readonly ocoBadges: Locator;
  /** "Queued" badges on market orders waiting for the open. */
  readonly queuedBadges: Locator;
  /** "Ext" badges on limit orders that may also fill in extended hours. */
  readonly extendedBadges: Locator;

  constructor(readonly page: Page) {
    this.card = page.getByTestId('open-orders');
    this.rows = page.getByTestId('open-order');
    this.ocoBadges = page.getByTestId('oco-badge');
    this.queuedBadges = page.getByTestId('queued-badge');
    this.extendedBadges = page.getByTestId('ext-badge');
  }

  /** The row for an order, by its title and size, e.g. ("Limit buy", 2). */
  row(title: string, quantity: number): Locator {
    return this.rows.filter({ hasText: `${title} · ${quantity}` });
  }

  /** Cancels an order from the card: the first tap asks, the second cancels. */
  async cancel(row: Locator): Promise<void> {
    await row.getByRole('button', { name: /^Cancel /, exact: false }).first().click();
    await row.getByRole('button', { name: 'Cancel order' }).click();
  }

  async edit(row: Locator, changes: { quantity?: number }): Promise<void> {
    await row.getByRole('button', { name: /^Edit / }).click();
    const form = row.getByTestId('edit-order');
    if (changes.quantity !== undefined) {
      await form.getByLabel('Shares').fill(String(changes.quantity));
    }
    await form.getByRole('button', { name: 'Save changes' }).click();
  }
}
