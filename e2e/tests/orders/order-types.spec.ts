import { test, expect } from '../../fixtures/test';
import { DashboardPage } from '../../pages/dashboard.page';
import { OpenOrders } from '../../pages/open-orders';

// Limit, stop, trailing, edit and cancel flows, and the Open orders card, end to end. Orders that
// wait for a price are placed well away from the market so they stay open, and every test cancels
// what it left behind so held cash and shares don't leak into the next test on this worker's account.
test.use({ persona: 'trader' });

const BUY_SYMBOL = 'MSFT';
const SELL_SYMBOL = 'TSLA';

test.afterEach(async ({ trader }) => {
  await trader.api.cancelAllOpenOrders();
  await trader.api.deleteAllTemplates();
});

async function openTicket(page: import('@playwright/test').Page, symbol: string) {
  const dashboard = new DashboardPage(page);
  await dashboard.goto();
  await dashboard.search.pick(symbol);
  await dashboard.trade.expectPriced();
  return { dashboard, ticket: dashboard.trade, openOrders: new OpenOrders(page) };
}

test.describe('Limit orders', () => {
  test('a limit buy below the market waits in Open orders @smoke', async ({ page, trader }) => {
    const { ticket, openOrders } = await openTicket(page, BUY_SYMBOL);

    await ticket.chooseType('Limit');
    await expect(ticket.limitPrice).toHaveValue(/\d/);
    await ticket.quantity.fill('2');
    await expect(ticket.sentence).toContainText(`Buy 2 shares of ${BUY_SYMBOL} at`);
    await ticket.reviewButton.click();
    await ticket.submitButton.click();

    await expect(ticket.result).toContainText('Limit order placed');
    await expect(ticket.result).toContainText('It\'s good for today');
    await ticket.done.click();
    await expect(openOrders.row('Limit buy', 2)).toBeVisible();
    expect((await trader.api.openOrders()).map(o => [o.symbol, o.orderType, o.quantity])).toContainEqual([BUY_SYMBOL, 'LIMIT', 2]);
  });

  test('an open buy holds its cost out of buying power', async ({ page, trader }) => {
    const before = await trader.api.buyingPower(trader.accountId);
    const { ticket } = await openTicket(page, BUY_SYMBOL);

    await ticket.chooseType('Limit');
    await ticket.quantity.fill('2');
    await ticket.reviewButton.click();
    await ticket.submitButton.click();
    await expect(ticket.result).toContainText('Set aside from buying power');

    expect((await trader.api.openOrders()).length).toBe(1);
    // Order-app counts the hold; the account's own cash is untouched until the order fills.
    expect(await trader.api.buyingPower(trader.accountId)).toBeCloseTo(before, 2);
  });

  test('shows how often the price was reached, as simulated history and not a prediction', async ({ page }) => {
    const { ticket } = await openTicket(page, BUY_SYMBOL);

    await ticket.chooseType('Limit');
    await expect(ticket.hitRate).toContainText(/traded at or below \$[\d,.]+ on \d+ of the (last 30 days|\d+ days available)/);
    await expect(ticket.hitRate).toContainText('Simulated past prices, not a prediction.');
  });

  test('previews what the order would do before it is reviewed', async ({ page }) => {
    const { ticket } = await openTicket(page, BUY_SYMBOL);

    await ticket.quantity.fill('1');
    await expect(ticket.impact).toContainText('New position');
    await expect(ticket.impact).toContainText('Cash left');
    await expect(ticket.impact).toContainText('New average cost');
  });
});

test.describe('Stop and trailing stop orders', () => {
  test.beforeEach(async ({ trader }) => {
    await trader.api.placeOrder({ accountId: trader.accountId, symbol: SELL_SYMBOL, side: 'BUY', quantity: 2 });
  });

  test('a sell stop below the market waits in Open orders', async ({ page }) => {
    const dashboard = new DashboardPage(page);
    const openOrders = new OpenOrders(page);
    await dashboard.goto();
    await dashboard.positionRow(SELL_SYMBOL).getByRole('button', { name: `Sell ${SELL_SYMBOL}`, exact: true }).click();
    await dashboard.trade.expectPriced();

    await dashboard.trade.chooseType('Stop');
    await dashboard.trade.quantity.fill('1');
    await expect(dashboard.trade.stopPrice).toHaveValue(/\d/);
    await expect(dashboard.trade.hitRate).toContainText('Your stop would have triggered on');
    await dashboard.trade.reviewButton.click();
    await dashboard.trade.submitButton.click();

    await expect(dashboard.trade.result).toContainText('Stop order placed');
    await dashboard.trade.done.click();
    await expect(openOrders.row('Stop sell', 1)).toBeVisible();
  });

  test('a trailing stop shows how far it trails', async ({ page }) => {
    const dashboard = new DashboardPage(page);
    const openOrders = new OpenOrders(page);
    await dashboard.goto();
    await dashboard.positionRow(SELL_SYMBOL).getByRole('button', { name: `Sell ${SELL_SYMBOL}`, exact: true }).click();
    await dashboard.trade.expectPriced();

    await dashboard.trade.chooseType('Trailing stop');
    await dashboard.trade.quantity.fill('1');
    await expect(dashboard.trade.trail).toHaveValue('5');
    await dashboard.trade.reviewButton.click();
    await dashboard.trade.submitButton.click();

    await expect(dashboard.trade.result).toContainText('Trailing stop order placed');
    await dashboard.trade.done.click();
    const row = openOrders.row('Trailing stop sell', 1);
    await expect(row).toBeVisible();
    await expect(row).toContainText('Trails 5%');
  });

  test('a sell stop above the market is blocked before review', async ({ page }) => {
    const dashboard = new DashboardPage(page);
    await dashboard.goto();
    await dashboard.positionRow(SELL_SYMBOL).getByRole('button', { name: `Sell ${SELL_SYMBOL}`, exact: true }).click();
    await dashboard.trade.expectPriced();

    await dashboard.trade.chooseType('Stop');
    await dashboard.trade.quantity.fill('1');
    await dashboard.trade.stopPrice.fill('999999');

    await expect(dashboard.trade.hint).toContainText('A sell stop must be below');
    await expect(dashboard.trade.reviewButton).toBeDisabled();
  });
});

test.describe('Editing and cancelling open orders', () => {
  async function placeLimitBuy(page: import('@playwright/test').Page, quantity: number) {
    const context = await openTicket(page, BUY_SYMBOL);
    await context.ticket.chooseType('Limit');
    await context.ticket.quantity.fill(String(quantity));
    await context.ticket.reviewButton.click();
    await context.ticket.submitButton.click();
    await expect(context.ticket.result).toContainText('Limit order placed');
    await context.ticket.done.click();
    return context;
  }

  test('an open order can be edited in place', async ({ page, trader }) => {
    const { openOrders } = await placeLimitBuy(page, 2);
    const before = (await trader.api.openOrders())[0];

    await openOrders.edit(openOrders.row('Limit buy', 2), { quantity: 3 });

    await expect(openOrders.row('Limit buy', 3)).toBeVisible();
    const after = await trader.api.openOrders();
    expect(after).toHaveLength(1);
    // Same order, changed in place: it keeps its id and its place in history.
    expect(after[0].orderId).toBe(before.orderId);
    expect(after[0].quantity).toBe(3);
  });

  test('cancelling takes a second tap, then releases the order', async ({ page, trader }) => {
    const { openOrders } = await placeLimitBuy(page, 2);
    const row = openOrders.row('Limit buy', 2);

    await row.getByRole('button', { name: /^Cancel / }).first().click();
    await expect(row.getByRole('button', { name: 'Keep' })).toBeVisible();
    await row.getByRole('button', { name: 'Keep' }).click();
    expect(await trader.api.openOrders()).toHaveLength(1);

    await openOrders.cancel(row);
    await expect(openOrders.rows).toHaveCount(0);
    expect(await trader.api.openOrders()).toHaveLength(0);
  });

  test('a cancelled order shows in order history', async ({ page, trader }) => {
    const { openOrders } = await placeLimitBuy(page, 1);
    await openOrders.cancel(openOrders.row('Limit buy', 1));
    await expect(openOrders.rows).toHaveCount(0);

    const history = await trader.api.orderHistory(0, 5);
    expect(history.content[0]).toMatchObject({ symbol: BUY_SYMBOL, status: 'CANCELLED' });
  });
});

test.describe('Bracket orders', () => {
  test('protecting a buy opens linked take-profit and stop-loss sells once it fills @smoke', async ({ page, trader }) => {
    const { ticket, openOrders } = await openTicket(page, BUY_SYMBOL);

    await ticket.quantity.fill('1');
    await ticket.bracketToggle.check();
    await expect(ticket.takeProfit).toHaveValue(/\d/);
    await expect(ticket.stopLoss).toHaveValue(/\d/);
    await expect(ticket.riskReward).toContainText(/Risk \$[\d,.]+ · Reward \$[\d,.]+ · 1 : [\d.]+/);
    await expect(ticket.sentence).toContainText('Once it fills, sell to take profit at');
    await ticket.reviewButton.click();
    await ticket.submitButton.click();
    await expect(ticket.result).toContainText(`Bought 1 share of ${BUY_SYMBOL}`);
    await ticket.done.click();

    await expect(openOrders.ocoBadges).toHaveCount(2);
    await expect(openOrders.rows.filter({ hasText: 'Limit sell · 1' })).toBeVisible();
    await expect(openOrders.rows.filter({ hasText: 'Stop sell · 1' })).toBeVisible();
    const exits = await trader.api.openOrders();
    expect(exits).toHaveLength(2);
    expect(exits[0].ocoGroupId).toBeTruthy();
    expect(exits[0].ocoGroupId).toBe(exits[1].ocoGroupId);
  });

  test('cancelling one leg leaves the other working as a normal order', async ({ page, trader }) => {
    await trader.api.placeOrderRaw({
      accountId: trader.accountId, symbol: BUY_SYMBOL, side: 'BUY', quantity: 1,
      bracketTakeProfitPrice: 99999, bracketStopLossPrice: 0.5
    });
    const { openOrders } = await openTicket(page, BUY_SYMBOL);
    await expect(openOrders.ocoBadges).toHaveCount(2);

    await openOrders.cancel(openOrders.rows.filter({ hasText: 'Stop sell · 1' }));

    await expect(openOrders.rows).toHaveCount(1);
    await expect(openOrders.ocoBadges).toHaveCount(0);
    expect(await trader.api.openOrders()).toHaveLength(1);
  });

  test('the toggle is not offered on a sell', async ({ page, trader }) => {
    await trader.api.placeOrder({ accountId: trader.accountId, symbol: SELL_SYMBOL, side: 'BUY', quantity: 1 });
    const dashboard = new DashboardPage(page);
    await dashboard.goto();
    await dashboard.positionRow(SELL_SYMBOL).getByRole('button', { name: `Sell ${SELL_SYMBOL}`, exact: true }).click();
    await dashboard.trade.expectPriced();

    await expect(dashboard.trade.bracketToggle).toHaveCount(0);
  });
});

test.describe('Order templates', () => {
  test('a reviewed setup is saved as a template, and applied again in one tap', async ({ page, trader }) => {
    const name = `E2E limit ${Date.now() % 100000}`;
    const { ticket } = await openTicket(page, BUY_SYMBOL);

    await ticket.chooseType('Limit');
    await ticket.untilCancelled.click();
    await ticket.quantity.fill('1');
    await ticket.reviewButton.click();
    await ticket.saveAsTemplate.click();
    await ticket.templateName.fill(name);
    await ticket.root.getByRole('button', { name: 'Save', exact: true }).click();
    await expect(ticket.templateMessage).toContainText(`Saved “${name}”.`);
    expect((await trader.api.templates()).map(t => t.name)).toContain(name);

    // Back to a blank market ticket, then apply it.
    await ticket.editButton.click();
    await ticket.chooseType('Market');
    await expect(ticket.limitPrice).toHaveCount(0);
    await ticket.templates.getByRole('button', { name: `Apply template ${name}` }).click();

    await expect(ticket.limitPrice).toHaveValue(/\d/);
    await expect(ticket.untilCancelled).toHaveAttribute('aria-checked', 'true');
  });
});
