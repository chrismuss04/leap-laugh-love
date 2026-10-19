import { expect, Locator, Page } from '@playwright/test';

/** The monthly recap: a story of cards, or a note when the month had nothing to recap. */
export class RecapPage {
  /** The card on screen; clicks and arrow keys move the story. */
  readonly stage: Locator;
  /** "2 / 6" under the card. */
  readonly counter: Locator;
  readonly empty: Locator;
  readonly error: Locator;

  constructor(readonly page: Page) {
    this.stage = page.locator('.stage:not(.skeleton)');
    this.counter = page.locator('.controls .counter');
    this.empty = page.getByTestId('recap-empty');
    this.error = page.getByTestId('recap-error');
  }

  async goto(): Promise<void> {
    await this.page.goto('/recap');
    await this.loaded();
  }

  /** Waits for the recap, or its empty note, or its error, to replace the loading card. */
  async loaded(): Promise<void> {
    await expect(this.stage.or(this.empty).or(this.error).first()).toBeVisible();
  }
}

/** Last month's full name in UTC, the month /api/reporting/recap/latest recaps: "September". */
export function lastRecapMonthName(now = new Date()): string {
  return new Date(Date.UTC(now.getUTCFullYear(), now.getUTCMonth() - 1, 1))
    .toLocaleString('en-US', { month: 'long', timeZone: 'UTC' });
}
