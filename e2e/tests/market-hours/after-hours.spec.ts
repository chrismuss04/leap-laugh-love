import { test, expect } from '../../fixtures/test';
import { DashboardPage } from '../../pages/dashboard.page';
import { OpenOrders } from '../../pages/open-orders';
import { Shell } from '../../pages/shell';
import { marketableBuyLimit, requireSession } from '../../fixtures/market-hours';

// The stack must be pinned to AFTER_HOURS (MARKET_HOURS_FORCE_SESSION=AFTER_HOURS): see "Market
// hours tests" in e2e/README.md. Pre-market works the same way for everything here.
test.use({ persona: 'trader' });

const SYMBOL = 'MSFT';

test.beforeEach(async ({ trader }) => {
  await requireSession(trader, 'AFTER_HOURS', 'PRE_MARKET');
});

test.afterEach(async ({ trader }) => {
  await trader.api.cancelAllOpenOrders();
});

async function openTicket(page: import('@playwright/test').Page) {
  const dashboard = new DashboardPage(page);
  await dashboard.goto();
  await dashboard.search.pick(SYMBOL);
  await dashboard.trade.expectPriced();
  return { dashboard, ticket: dashboard.trade, openOrders: new OpenOrders(page) };
}

test.describe('After-hours', () => {
  test('the header says which session it is, and the card lays out the day', async ({ page }) => {
    await new DashboardPage(page).goto();
    const shell = new Shell(page);

    await expect(shell.marketStatus).toContainText(/After-hours|Pre-market/);
    await shell.marketStatus.click();
    await expect(shell.marketHours).toContainText('Regular hours');
    await expect(shell.marketHours).toContainText('Next regular open');
  });

  test('quotes are thin: the spread is wider than in regular hours', async ({ trader }) => {
    const quote = await trader.api.latestQuote(SYMBOL);

    // Regular hours quote 5 basis points wide; extended hours four times that.
    expect((quote.askPrice - quote.bidPrice) / quote.lastPrice).toBeGreaterThan(0.0015);
  });

  test('the ticket says so, and a market order is queued for the open instead of filling', async ({ page, trader }) => {
    const before = await trader.api.buyingPower(trader.accountId);
    const { ticket, openOrders } = await openTicket(page);

    await expect(ticket.sessionTag).toBeVisible();
    await expect(ticket.sessionStrip).toContainText('Next open');
    await expect(ticket.sessionNote).toContainText('queued and placed at the open');
    await ticket.quantity.fill('2');
    await expect(ticket.collarRow).toContainText('Up to');
    await expect(ticket.sentence).toContainText(`Buy 2 shares of ${SYMBOL} at the market open`);
    await ticket.reviewButton.click();
    await ticket.submitButton.click();

    await expect(ticket.result).toContainText('Queued for market open');
    await ticket.done.click();
    await expect(openOrders.queuedBadges).toHaveCount(1);
    // Nothing moved: the order only holds cash while it waits.
    expect(await trader.api.buyingPower(trader.accountId)).toBeCloseTo(before, 2);
  });

  test('a limit order can opt in to extended hours and fills right away when marketable', async ({ page, trader }) => {
    const ask = (await trader.api.latestQuote(SYMBOL)).askPrice;
    const sharesBefore = await trader.api.sharesHeld(trader.accountId, SYMBOL);
    const { ticket } = await openTicket(page);

    await ticket.chooseType('Limit');
    // On by default while the market is in an extended session.
    await expect(ticket.extendedHours).toBeChecked();
    await ticket.limitPrice.fill(marketableBuyLimit(ask));
    await ticket.quantity.fill('1');
    await expect(ticket.sentence).toContainText('including extended hours');
    await ticket.reviewButton.click();
    await ticket.submitButton.click();

    await expect(ticket.result).toContainText(`Bought 1 share of ${SYMBOL}`);
    expect(await trader.api.sharesHeld(trader.accountId, SYMBOL)).toBe(sharesBefore + 1);
  });

  test('a limit order without extended hours waits for regular trading, even when marketable', async ({ page, trader }) => {
    const ask = (await trader.api.latestQuote(SYMBOL)).askPrice;
    const { ticket, openOrders } = await openTicket(page);

    await ticket.chooseType('Limit');
    await ticket.extendedHours.uncheck();
    await expect(ticket.sessionNote).toContainText('Without extended hours');
    await ticket.limitPrice.fill(marketableBuyLimit(ask));
    await ticket.quantity.fill('1');
    await ticket.reviewButton.click();
    await ticket.submitButton.click();

    await expect(ticket.result).toContainText('Limit order placed');
    await ticket.done.click();
    await expect(openOrders.row('Limit buy', 1)).toContainText('waiting for market hours');
    await expect(openOrders.extendedBadges).toHaveCount(0);
  });

  test('stops only trigger in regular hours, and the ticket says so', async ({ page }) => {
    const { ticket } = await openTicket(page);

    await ticket.chooseType('Stop');
    await expect(ticket.sessionNote).toContainText('Stops only trigger during regular market hours');
    await expect(ticket.extendedHours).toHaveCount(0);
  });

  test('extended hours is for limit orders only: the API refuses it on anything else', async ({ trader }) => {
    const response = await trader.api.placeOrderRaw({
      accountId: trader.accountId, symbol: SYMBOL, side: 'BUY', quantity: 1, extendedHours: true
    });

    expect(response.status()).toBe(400);
  });
});
