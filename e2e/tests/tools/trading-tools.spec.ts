import { test, expect } from '../../fixtures/test';
import { personas } from '../../data/users';

const TOOLS = [
  { id: 'rebalance', name: 'Rebalance Donut', surface: 'app-rebalance-tool' },
  { id: 'raise-cash', name: 'Raise Cash', surface: 'app-raise-cash-tool' },
  { id: 'basket', name: 'Basket Builder', surface: 'app-basket-tool' },
  { id: 'draw', name: 'Draw Your Trade', surface: 'app-draw-tool' }
] as const;

test.describe('Trading Tools hub', () => {
  test.use({ persona: 'alice' });

  test('shows every tool preview and opens each tool page', async ({ page }) => {
    await page.goto('/tools');
    await expect(page.getByRole('heading', { name: 'Trading Tools' })).toBeVisible();

    const tiles = page.locator('a.tile[data-tool]');
    await expect(tiles).toHaveCount(TOOLS.length);
    for (const tool of TOOLS) {
      const tile = page.locator(`a.tile[data-tool="${tool.id}"]`);
      await expect(tile).toContainText(tool.name);
      await expect(tile.locator('app-tool-preview')).toBeVisible();
      await tile.click();

      await expect(page).toHaveURL(new RegExp(`/tools/${tool.id}(?:\\?|$)`));
      await expect(page.getByRole('heading', { name: tool.name })).toBeVisible();
      await expect(page.locator(tool.surface)).toBeVisible();

      await page.getByRole('link', { name: /All tools/ }).click();
      await expect(page.getByRole('heading', { name: 'Trading Tools' })).toBeVisible();
    }
  });
});

test.describe('Tools account and plan context', () => {
  test.use({ persona: 'multi' });

  test('keeps the plan with its account and asks before switching accounts', async ({ page, api, tokenFor }) => {
    const multi = api.as(await tokenFor(personas.multi.email));
    const [planAccount, otherAccount] = await multi.accounts();
    expect(planAccount).toBeTruthy();
    expect(otherAccount).toBeTruthy();

    await page.goto(`/tools?account=${planAccount.accountId}`);
    await page.locator('a.tile[data-tool="rebalance"]').click();
    await expect(page.getByRole('heading', { name: 'Rebalance Donut' })).toBeVisible();

    // The seeded multi-account client has cash but no holdings, so add one holding to make a plan.
    await page.getByTestId('add-holding').click();
    const search = page.getByRole('combobox', { name: 'Search symbols' });
    await search.fill('NVDA');
    const result = page.getByRole('option').filter({ hasText: 'NVDA' });
    await expect(result).toBeVisible();
    await result.click();
    await expect(page.locator('[data-target-input="NVDA"]')).toHaveValue('5');
    await expect(page.getByTestId('trade-preview').locator('[data-trade="NVDA"]')).toContainText('Buy');
    await page.getByTestId('add-to-plan').click();

    // The plan is browser-side state and should survive both changing tools and a page reload.
    await page.getByTestId('tray-toggle').click();
    await expect(page.getByTestId('tray-panel')).toContainText('NVDA');
    await page.getByRole('link', { name: 'All tools' }).click();
    await page.reload();
    await expect(page.getByTestId('tray-summary')).toContainText('1 order');

    const accounts = page.getByRole('navigation', { name: 'Accounts' });
    await accounts.locator(`a[href*="${otherAccount.accountId}"]`).click();
    await expect(page.getByTestId('switch-dialog')).toBeVisible();
    await expect(page.getByRole('heading', { name: 'Keep your trade plan?' })).toBeVisible();
    await page.getByTestId('stay').click();
    await expect(page).toHaveURL(new RegExp(`account=${planAccount.accountId}`));
    await expect(page.getByTestId('switch-dialog')).toBeHidden();

    await accounts.locator(`a[href*="${otherAccount.accountId}"]`).click();
    await expect(page.getByTestId('switch-dialog')).toBeVisible();
    await page.getByTestId('clear-and-switch').click();
    await expect(page).toHaveURL(new RegExp(`account=${otherAccount.accountId}`));
    await expect(page.getByTestId('switch-dialog')).toBeHidden();
    await expect(page.getByTestId('tray-summary')).toContainText('no orders yet');
  });
});

test.describe('Rebalance order journey', () => {
  test.use({ persona: 'trader' });

  test('adds a stock to the donut, reviews the plan, and submits its order', async ({ page, trader }) => {
    await page.goto('/tools');
    await page.locator('a.tile[data-tool="rebalance"]').click();
    await expect(page.getByRole('heading', { name: 'Rebalance Donut' })).toBeVisible();
    await expect(page.getByTestId('add-stock')).toBeVisible();

    // Pick an instrument this worker's account does not already hold; other E2E specs share the
    // worker account, so this keeps the added slice and its expected buy deterministic.
    const positions = await trader.api.positions(trader.accountId);
    const candidates = ['NVDA', 'META', 'GOOGL', 'COST', 'JPM', 'AMZN', 'MSFT', 'AAPL'];
    const symbol = candidates.find(candidate => !positions.positions.some(p => p.symbol === candidate));
    expect(symbol, 'the E2E trader should have at least one unowned seeded stock').toBeTruthy();
    const before = await trader.api.sharesHeld(trader.accountId, symbol!);

    await page.getByTestId('add-stock').click();
    await page.getByRole('combobox', { name: 'Search symbols' }).fill(symbol!);
    const result = page.getByRole('option').filter({ hasText: symbol! }).first();
    await expect(result).toBeVisible();
    await result.click();

    const target = page.locator(`[data-target-input="${symbol}"]`);
    await expect(target).toHaveValue('5');
    await expect(page.getByTestId('trade-preview').locator(`[data-trade="${symbol}"]`)).toContainText('Buy');
    await page.getByTestId('add-to-plan').click();

    await page.getByTestId('tray-toggle').click();
    const tray = page.getByTestId('tray-panel');
    const orders = tray.locator('li.order');
    await expect(orders.filter({ hasText: symbol! })).toHaveCount(1);

    // Keep only this added-stock order in the plan, independent of the worker's other holdings.
    // Remove from the end so deleting one row doesn't shift the next locator's index.
    const unrelatedOrders = orders.filter({ hasNotText: symbol! });
    for (let i = await unrelatedOrders.count() - 1; i >= 0; i--) {
      await unrelatedOrders.nth(i).getByRole('button', { name: /^Remove / }).click();
    }
    const stockOrder = orders.filter({ hasText: symbol! });
    const quantity = stockOrder.getByLabel(`Shares of ${symbol}`);
    await quantity.fill('1');
    await quantity.press('Tab');
    await expect(quantity).toHaveValue('1');

    await page.getByTestId('review-plan').click();
    const review = page.getByTestId('review-sheet');
    await expect(review.getByRole('heading', { name: 'Review plan' })).toBeVisible();
    await expect(review.locator(`tr[data-symbol="${symbol}"]`)).toContainText('1');
    await page.getByTestId('submit').click();
    await expect(review.locator(`li[data-symbol="${symbol}"]`)).toHaveAttribute('data-status', 'filled');
    await expect(page.getByTestId('review-summary')).toContainText('1 filled');

    await expect.poll(() => trader.api.sharesHeld(trader.accountId, symbol!)).toBe(before + 1);
    await expect(page.getByTestId('tray-summary')).toContainText('no orders yet');
  });
});
