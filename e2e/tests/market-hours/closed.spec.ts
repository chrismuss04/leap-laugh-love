import { test, expect } from '../../fixtures/test';
import { DashboardPage } from '../../pages/dashboard.page';
import { OpenOrders } from '../../pages/open-orders';
import { Shell } from '../../pages/shell';
import { marketableBuyLimit, requireSession } from '../../fixtures/market-hours';

// The stack must be pinned to CLOSED (MARKET_HOURS_FORCE_SESSION=CLOSED): see "Market hours tests"
// in e2e/README.md. Give it a few minutes (MARKET_HOURS_FORCE_MINUTES) and opening.spec.ts watches
// it open afterwards; these specs must finish before then, and skip themselves if they didn't.
test.use({ persona: 'trader' });

const SYMBOL = 'MSFT';

test.beforeEach(async ({ trader }) => {
  await requireSession(trader, 'CLOSED');
});

test.afterEach(async ({ trader }) => {
  await trader.api.cancelAllOpenOrders();
});

async function openTicket(page: import('@playwright/test').Page) {
  const dashboard = new DashboardPage(page);
  await dashboard.goto();
  await dashboard.search.pick(SYMBOL);
  await dashboard.trade.expectPriced();
  return { ticket: dashboard.trade, openOrders: new OpenOrders(page) };
}

test.describe('Market closed', () => {
  test('the header says the market is closed and when it opens', async ({ page }) => {
    await new DashboardPage(page).goto();
    const shell = new Shell(page);

    await expect(shell.marketStatus).toContainText('Market closed');
    await expect(shell.marketStatus).toContainText(/Opens/);
  });

  test('prices stand still while the market is closed, but quotes stay fresh', async ({ trader }) => {
    const first = await trader.api.latestQuote(SYMBOL);

    // A newer quote arrives...
    await expect
      .poll(async () => (await trader.api.latestQuote(SYMBOL)).quoteTimestamp, { timeout: 15_000 })
      .not.toBe(first.quoteTimestamp);

    // ...at the same price, and with the regular spread.
    const later = await trader.api.latestQuote(SYMBOL);
    expect(later.lastPrice).toBe(first.lastPrice);
    expect((later.askPrice - later.bidPrice) / later.lastPrice).toBeLessThan(0.001);
  });

  test('a market buy is queued for the open, holding cash at a collar 5% above the ask', async ({ page, trader }) => {
    const before = await trader.api.buyingPower(trader.accountId);
    const { ticket, openOrders } = await openTicket(page);

    await expect(ticket.sessionTag).toContainText('Closed');
    await ticket.quantity.fill('2');
    await expect(ticket.collarRow).toContainText('Up to');
    await expect(ticket.reviewButton).toHaveText('Review queued order');
    await ticket.reviewButton.click();
    await expect(ticket.submitButton).toHaveText('Queue buy');
    await ticket.submitButton.click();

    await expect(ticket.result).toContainText('Queued for market open');
    await expect(ticket.result).toContainText('Price collar');
    await ticket.done.click();
    await expect(openOrders.queuedBadges).toHaveCount(1);
    await expect(openOrders.card).toContainText('Executes at the open');

    const [queued] = await trader.api.openOrders();
    const ask = (await trader.api.latestQuote(SYMBOL)).askPrice;
    expect(queued).toMatchObject({ symbol: SYMBOL, orderType: 'MARKET', queued: true });
    expect(queued.collarPrice! / ask).toBeGreaterThan(1.0499);
    expect(queued.collarPrice! / ask).toBeLessThan(1.0501);
    // Order-app holds the cash; the account's own cash is untouched until it fills.
    expect(await trader.api.buyingPower(trader.accountId)).toBeCloseTo(before, 2);
  });

  test('a queued order can be cancelled before the open', async ({ page, trader }) => {
    const { ticket, openOrders } = await openTicket(page);
    await ticket.quantity.fill('1');
    await ticket.reviewButton.click();
    await ticket.submitButton.click();
    await expect(ticket.result).toContainText('Queued for market open');
    await ticket.done.click();

    await openOrders.cancel(openOrders.row('Market buy', 1));

    await expect(openOrders.queuedBadges).toHaveCount(0);
    expect(await trader.api.openOrders()).toEqual([]);
  });

  test('a queued order must be affordable at its collar, not just at the price', async ({ trader }) => {
    const ask = (await trader.api.latestQuote(SYMBOL)).askPrice;
    const power = await trader.api.buyingPower(trader.accountId);
    // Fits the account at the ask, but not at 5% above it.
    const quantity = Math.floor(power / ask / 1.02);

    const response = await trader.api.placeOrderRaw({ accountId: trader.accountId, symbol: SYMBOL, side: 'BUY', quantity });

    expect(response.ok()).toBe(true);
    expect((await response.json()).status).toBe('REJECTED');
  });

  test('a marketable limit order waits while closed instead of filling', async ({ page, trader }) => {
    const ask = (await trader.api.latestQuote(SYMBOL)).askPrice;
    const response = await trader.api.placeOrderRaw({
      accountId: trader.accountId, symbol: SYMBOL, side: 'BUY', quantity: 1,
      orderType: 'LIMIT', limitPrice: Number(marketableBuyLimit(ask))
    });
    expect((await response.json()).status).toBe('OPEN');

    await new DashboardPage(page).goto();

    await expect(new OpenOrders(page).row('Limit buy', 1)).toContainText('waiting for market hours');
  });

  test('even an extended-hours limit order waits while the market is fully closed', async ({ trader }) => {
    const ask = (await trader.api.latestQuote(SYMBOL)).askPrice;
    const response = await trader.api.placeOrderRaw({
      accountId: trader.accountId, symbol: SYMBOL, side: 'BUY', quantity: 1,
      orderType: 'LIMIT', limitPrice: Number(marketableBuyLimit(ask)), extendedHours: true
    });

    expect((await response.json()).status).toBe('OPEN');
  });
});
