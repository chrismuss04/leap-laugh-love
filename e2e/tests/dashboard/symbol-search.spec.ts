import { test, expect } from '../../fixtures/test';
import { DashboardPage } from '../../pages/dashboard.page';
import { SymbolSearch } from '../../pages/symbol-search';

test.use({ persona: 'alice' });

test.describe('Symbol search', () => {
  let dashboard: DashboardPage;
  let search: SymbolSearch;

  test.beforeEach(async ({ page }) => {
    dashboard = new DashboardPage(page);
    search = dashboard.search;
    await dashboard.goto();
  });

  test('typing a ticker lists matches, exact match first @smoke', async () => {
    await search.input.fill('MS');
    await expect(search.options.first()).toBeVisible();
    // Prefix matches rank above substring matches (e.g. MSFT before anything merely containing MS).
    await expect(search.options.first().locator('.symbol')).toHaveText(/^MS/);
    expect(await search.options.count()).toBeLessThanOrEqual(8);

    await search.input.fill('AAPL');
    await expect(search.options.first().locator('.symbol')).toHaveText('AAPL');
    await expect(search.options.first().locator('.price')).toHaveText(/\$/);
  });

  test('company names are searchable too', async () => {
    await search.input.fill('Apple');
    await expect(search.option('AAPL')).toBeVisible();
    await expect(search.option('AAPL').locator('.name')).toHaveText(/Apple/);
  });

  test('a search with no matches says so', async () => {
    await search.input.fill('ZZZZQ');
    await expect(search.listbox).toContainText('No symbols match "ZZZZQ"');
  });

  test('clicking a result opens it in the trade panel with the shares field focused', async () => {
    await search.pick('MSFT');

    await expect(dashboard.trade.title).toHaveText('Buy MSFT');
    await expect(dashboard.trade.quantity).toBeFocused();
    await expect(search.input).toHaveValue('');
    await expect(search.listbox).toBeHidden();
  });

  test('arrow keys and Enter pick a result', async () => {
    await search.input.fill('GOOG');
    await expect(search.options.first()).toBeVisible();
    const count = await search.options.count();

    await search.input.press('ArrowDown');
    await expect(search.options.nth(count > 1 ? 1 : 0)).toHaveAttribute('aria-selected', 'true');
    await search.input.press('ArrowUp');
    await expect(search.options.first()).toHaveAttribute('aria-selected', 'true');
    const symbol = (await search.options.first().locator('.symbol').textContent())!.trim();

    await search.input.press('Enter');
    await expect(dashboard.trade.title).toHaveText(`Buy ${symbol}`);
  });

  test('Escape closes the results', async () => {
    await search.input.fill('AA');
    await expect(search.listbox).toBeVisible();

    await search.input.press('Escape');
    await expect(search.listbox).toBeHidden();
    await expect(search.input).not.toBeFocused();
  });

  test('"/" focuses search from anywhere, but not while typing elsewhere', async ({ page }) => {
    await page.evaluate(() => (document.activeElement as HTMLElement | null)?.blur());
    await page.keyboard.press('/');
    await expect(search.input).toBeFocused();
    await expect(search.input).toHaveValue('');

    // In the shares field "/" must type, not jump away.
    await search.pick('AAPL');
    await dashboard.trade.quantity.focus();
    await page.keyboard.press('/');
    await expect(dashboard.trade.quantity).toBeFocused();
  });
});
