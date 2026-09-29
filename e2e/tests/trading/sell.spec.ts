import { test, expect } from '../../fixtures/test';
import { DashboardPage } from '../../pages/dashboard.page';
import { HoldingsPage } from '../../pages/holdings.page';

test.use({ persona: 'trader' });

// Each test buys through the API what it then sells, from this worker's own account.
test.describe('Selling', () => {
  test('selling a whole position removes it everywhere @smoke', async ({ page, trader }) => {
    const symbol = 'TSLA';
    await trader.api.placeOrder({ accountId: trader.accountId, symbol, side: 'BUY', quantity: 1 });
    const held = await trader.api.sharesHeld(trader.accountId, symbol);

    const dashboard = new DashboardPage(page);
    await dashboard.goto();
    await dashboard.positionRow(symbol).getByRole('button', { name: `Sell ${symbol}`, exact: true }).click();
    await expect(dashboard.trade.title).toHaveText(`Sell ${symbol}`);
    await dashboard.trade.expectPriced();
    await dashboard.trade.sellAll.click();
    await dashboard.trade.reviewButton.click();
    await dashboard.trade.submitButton.click();

    await expect(dashboard.trade.result).toContainText(`Sold ${held} share`);
    expect(await trader.api.sharesHeld(trader.accountId, symbol)).toBe(0);

    await dashboard.trade.done.click();
    await expect(dashboard.positionRow(symbol)).toHaveCount(0);

    const holdings = new HoldingsPage(page);
    await holdings.goto();
    await expect(holdings.position(trader.accountNumber, symbol)).toHaveCount(0);
  });

  test('selling more than is held is blocked before review', async ({ page, trader }) => {
    const symbol = 'AAPL';
    await trader.api.placeOrder({ accountId: trader.accountId, symbol, side: 'BUY', quantity: 1 });
    const held = await trader.api.sharesHeld(trader.accountId, symbol);

    const dashboard = new DashboardPage(page);
    await dashboard.goto();
    await dashboard.positionRow(symbol).getByRole('button', { name: `Sell ${symbol}`, exact: true }).click();
    await dashboard.trade.quantity.fill(String(held + 1));

    await expect(dashboard.trade.hint).toHaveText(`You only have ${held} ${held === 1 ? 'share' : 'shares'} to sell`);
    await expect(dashboard.trade.reviewButton).toBeDisabled();
  });
});
