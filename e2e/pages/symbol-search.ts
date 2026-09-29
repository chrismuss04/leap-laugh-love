import { expect, Locator, Page } from '@playwright/test';

export class SymbolSearch {
  readonly input: Locator;
  readonly listbox: Locator;
  readonly options: Locator;

  constructor(readonly page: Page) {
    this.input = page.getByRole('combobox', { name: 'Search symbols' });
    this.listbox = page.getByRole('listbox');
    this.options = this.listbox.getByRole('option');
  }

  option(symbol: string): Locator {
    return this.options.filter({ has: this.page.locator('.symbol').getByText(symbol, { exact: true }) });
  }

  /** Searches and clicks the exact symbol, opening it in the trade panel. */
  async pick(symbol: string): Promise<void> {
    // Retried because of a known race in SymbolSearchComponent: onBlur() closes the list 150ms
    // later without cancelling on re-focus, so searching again within 150ms of leaving the box
    // (e.g. pick, type shares, search) opens the results and then shuts them - and typing won't
    // reopen them, only a fresh focus does. So a retry leaves the box, lets that timer run out,
    // and comes back. Remove all of this once onFocus() clears the timer.
    let retry = false;
    await expect(async () => {
      if (retry) {
        await this.input.blur();
        // eslint-disable-next-line playwright/no-wait-for-timeout -- outlasting the app's 150ms blur timer
        await this.page.waitForTimeout(250);
      }
      retry = true;
      await this.input.fill(symbol);
      await this.option(symbol).click({ timeout: 2_000 });
    }).toPass({ timeout: 15_000 });
  }
}
