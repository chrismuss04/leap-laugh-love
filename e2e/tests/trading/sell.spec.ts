import { test, expect } from '../../fixtures/test';
import { DashboardPage } from '../../pages/dashboard.page';
import { HoldingsPage } from '../../pages/holdings.page';

test.use({ persona: 'trader' });

// Each test sells what it bought itself, from this worker's own account. Different symbols per
// test would not be needed for isolation (tests in a worker run one at a time) but keep the
// intent of each obvious in the order history when debugging.
test.describe('Selling', () => {
  test('a position row\'s Sell button opens the ticket on the sell side, and "Sell all" fills the holding', async ({ page, trader }) => {
    const symbol = 'GOOGL';
    await trader.api.placeOrder({ accountId: trader.accountId, symbol, side: 'BUY', quantity: 2 });
    const held = await trader.api.sharesHeld(trader.accountId, symbol);

    const dashboard = new DashboardPage(page);
    await dashboard.goto();
    await dashboard.positionRow(symbol).getByRole('button', { name: `Sell ${symbol}`, exact: true }).click();

    const ticket = dashboard.trade;
    await expect(ticket.title).toHaveText(`Sell ${symbol}`);
    await expect(ticket.sellSide).toHaveAttribute('aria-checked', 'true');
    await expect(ticket.hint).toContainText(`${held.toLocaleString('en-US')} shares available`);
    await expect(ticket.estimate.locator('xpath=..')).toContainText('Estimated credit');

    await ticket.sellAll.click();
    await expect(ticket.quantity).toHaveValue(String(held));
  });

  test('selling a whole position removes it everywhere @smoke', async ({ page, trader }) => {
    const symbol = 'TSLA';
    await trader.api.placeOrder({ accountId: trader.accountId, symbol, side: 'BUY', quantity: 1 });
    const held = await trader.api.sharesHeld(trader.accountId, symbol);

    const dashboard = new DashboardPage(page);
    await dashboard.goto();
    await dashboard.positionRow(symbol).getByRole('button', { name: `Sell ${symbol}`, exact: true }).click();
    await dashboard.trade.expectPriced();
    await dashboard.trade.sellAll.click();
    await dashboard.trade.reviewButton.click();
    await expect(dashboard.trade.reviewText).toContainText(`to sell ${held} share`);
    await dashboard.trade.submitButton.click();

    await expect(dashboard.trade.result).toContainText(`Sold ${held} share`);
    await expect(dashboard.trade.result).toContainText('Total credit');
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

  test('selling a symbol that is not held is blocked', async ({ page, trader }) => {
    const symbol = 'NVDA';
    expect(await trader.api.sharesHeld(trader.accountId, symbol)).toBe(0);

    const dashboard = new DashboardPage(page);
    await dashboard.goto();
    await dashboard.search.pick(symbol);
    await dashboard.trade.sellSide.click();
    await dashboard.trade.quantity.fill('1');

    await expect(dashboard.trade.hint).toHaveText(`You don't own any ${symbol} in this account`);
    await expect(dashboard.trade.reviewButton).toBeDisabled();
  });
});
