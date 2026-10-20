import { test, expect, Trader } from '../../fixtures/test';
import { DrawPage } from '../../pages/draw.page';
import { MONEY, money } from '../../pages/format';

// Draw Your Trade: sketch where a price goes and place the trade it makes. Every test trades from
// this worker's own funded account; ledgers are append-only, so assertions compare before and
// after. Prices are live, so levels are read off the chart, never assumed. Orders left waiting -
// a limit buy, a filled buy's bracket - are cancelled after each test so they don't hold cash.
test.use({ persona: 'trader' });

const SYMBOL = 'AAPL';

/** A stock the trader holds none of: one the other trading specs rarely buy. */
async function unheld(trader: Trader): Promise<string | undefined> {
  const candidates = ['JPM', 'COST', 'META', 'GOOGL', 'TSLA'];
  const held = await Promise.all(candidates.map(symbol => trader.api.sharesHeld(trader.accountId, symbol)));
  return candidates.find((_, i) => held[i] === 0);
}

test.describe('Draw Your Trade', () => {
  let draw: DrawPage;

  test.beforeEach(async ({ page }) => {
    draw = new DrawPage(page);
  });

  test.afterEach(async ({ trader }) => {
    await trader.api.cancelAllOpenOrders();
  });

  test('a rally drawn by hand becomes a market buy with its bracket @smoke', async ({ trader }) => {
    await draw.goto();
    await draw.pick(SYMBOL);
    await expect(draw.hint).toBeVisible();

    await draw.drawLine(260, 90);
    await expect(draw.hint).toBeHidden();
    await expect(draw.badge).toHaveText('Ride the rally');
    await expect(draw.cone).toBeVisible();
    await expect(draw.sentence).toHaveText(new RegExp(`^Buy \\d+ ${SYMBOL} now at about \\$`));
    // A market buy's odds: reaching the target, and which exit fills first.
    await expect(draw.odds).toHaveCount(2);
    await expect(draw.boldness).toHaveText(/^A (typical move|bold call|moonshot) · \d+\.\d+σ$/);

    expect(await draw.sharesTo('min')).toBe(1);
    await expect(draw.sentence).toHaveText(new RegExp(`^Buy 1 ${SYMBOL} now`));
    const takeProfit = await draw.level('target');
    const stopLoss = await draw.level('stop');
    expect(takeProfit).toBeGreaterThan(stopLoss);
    await expect(draw.terms).toHaveText(`Take profit ${money(takeProfit)} / stop loss ${money(stopLoss)}`);

    await draw.addToPlan.click();
    await expect(draw.addToPlan).toHaveText('In your plan ✓');
    await expect(draw.addToPlan).toBeDisabled();
    await expect.poll(() => draw.trayLines()).toEqual([`BUY 1 ${SYMBOL} (Draw)`]);
    await expect(draw.trayTerms()).toHaveText(`Take profit ${money(takeProfit)} / stop loss ${money(stopLoss)}`);

    const sharesBefore = await trader.api.sharesHeld(trader.accountId, SYMBOL);
    await draw.reviewAndSubmit();
    await expect(draw.results).toHaveCount(1);
    await expect(draw.results.first()).toHaveAttribute('data-status', 'filled');
    expect(await trader.api.sharesHeld(trader.accountId, SYMBOL)).toBe(sharesBefore + 1);

    // Once the buy fills, order-app opens its exits: a sell limit at the take profit and a sell
    // stop at the stop loss, cancelling each other.
    await expect.poll(async () => {
      const exits = (await trader.api.openOrders())
        .filter(o => o.symbol === SYMBOL && o.side === 'SELL' && o.parentOrderId)
        .filter(o => (o.orderType === 'LIMIT' && Number(o.limitPrice) === takeProfit)
          || (o.orderType === 'STOP' && Number(o.stopPrice) === stopLoss));
      return exits.map(o => o.orderType).sort();
    }, { timeout: 15_000 }).toEqual(['LIMIT', 'STOP']);
  });

  test('a dip preset waits as a limit buy, good until cancelled, with its bracket', async ({ trader }) => {
    await draw.goto();
    await draw.pick(SYMBOL);
    await draw.horizon('This week');

    await draw.preset('dip');
    await expect(draw.badge).toHaveText('Buy the dip');
    // Dipping first adds the chance the limit fills at all.
    await expect(draw.odds).toHaveCount(3);
    expect(await draw.sharesTo('min')).toBe(1);

    const entry = await draw.level('entry');
    const takeProfit = await draw.level('target');
    const stopLoss = await draw.level('stop');
    await expect(draw.sentence).toHaveText(
      `Buy 1 ${SYMBOL} if it dips to ${money(entry)}. Sell at ${money(takeProfit)} for ` +
      `+${money(takeProfit - entry)}, or bail at ${money(stopLoss)} for −${money(entry - stopLoss)}.`);
    await expect(draw.terms).toHaveText(
      `Limit ${money(entry)} · GTC · Take profit ${money(takeProfit)} / stop loss ${money(stopLoss)}`);

    await draw.addToPlan.click();
    await draw.reviewAndSubmit();
    await expect(draw.results.first()).toHaveAttribute('data-status', 'open');

    const waiting = (await trader.api.openOrders())
      .find(o => o.symbol === SYMBOL && o.side === 'BUY' && o.orderType === 'LIMIT' && Number(o.limitPrice) === entry);
    expect(waiting, 'the limit buy is waiting').toBeDefined();
    expect(waiting!.timeInForce).toBe('GTC');
    expect(Number(waiting!.bracketTakeProfitPrice)).toBe(takeProfit);
    expect(Number(waiting!.bracketStopLossPrice)).toBe(stopLoss);
  });

  test('dragging a level changes the trade, and the plan takes the change', async () => {
    await draw.goto();
    await draw.pick(SYMBOL);
    await draw.preset('rally');
    await draw.sharesTo('min');
    await draw.addToPlan.click();
    await expect(draw.addToPlan).toHaveText('In your plan ✓');

    const before = await draw.level('target');
    const tag = draw.page.getByRole('slider', { name: 'Take profit' });
    await tag.focus();
    await draw.page.keyboard.press('Shift+ArrowUp');
    await expect.poll(() => draw.level('target')).toBeGreaterThan(before);
    await expect(draw.addToPlan).toHaveText('Update plan');

    await draw.addToPlan.click();
    const after = await draw.level('target');
    await expect.poll(() => draw.trayLines()).toEqual([`BUY 1 ${SYMBOL} (Draw)`]);
    await expect(draw.trayTerms()).toContainText(`Take profit ${money(after)}`);
  });

  test('a fall drawn on a held stock sells it', async ({ trader }) => {
    await trader.api.placeOrder({ accountId: trader.accountId, symbol: SYMBOL, side: 'BUY', quantity: 2 });
    const held = await trader.api.sharesHeld(trader.accountId, SYMBOL);
    await draw.goto();
    await draw.pick(SYMBOL);

    await draw.drawLine(240, -90);
    await expect(draw.badge).toHaveText('Get out early');
    // A sell starts at every share held.
    await expect(draw.size).toContainText(`${held} held`);
    await expect(draw.sentence).toHaveText(new RegExp(`^Sell ${held} ${SYMBOL} now at about \\$[\\d,]+\\.\\d{2}, dodging a `));
    await expect(draw.odds).toHaveCount(1);

    expect(await draw.sharesTo('min')).toBe(1);
    await draw.addToPlan.click();
    await expect.poll(() => draw.trayLines()).toEqual([`SELL 1 ${SYMBOL} (Draw)`]);
    await draw.reviewAndSubmit();
    await expect(draw.results.first()).toHaveAttribute('data-status', 'filled');
    expect(await trader.api.sharesHeld(trader.accountId, SYMBOL)).toBe(held - 1);
  });

  test('a fall on a stock the account doesn\'t hold explains itself instead of trading', async ({ trader }) => {
    const symbol = await unheld(trader);
    expect(symbol, 'a stock this trader holds none of').toBeDefined();

    await draw.goto();
    await draw.pick(symbol!);
    await draw.drawLine(240, -90);
    await expect(draw.sentence).toHaveText(`You'd need to own ${symbol} to sell it. Draw a line that ends higher to buy instead.`);
    await expect(draw.addToPlan).toHaveCount(0);
    await expect(draw.surface.locator('.level-tag')).toHaveCount(0);

    // Redraw starts over.
    await draw.redraw.click();
    await expect(draw.card).toBeHidden();
    await expect(draw.hint).toBeVisible();
  });

  test('shows the live price and a chart for the stock picked', async () => {
    await draw.goto();
    await draw.pick(SYMBOL);
    await expect(draw.price).toHaveText(MONEY);
    await expect(draw.surface.locator('.history-line')).toHaveAttribute('d', /^M/);
  });
});
