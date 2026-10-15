import { test, expect } from '../../fixtures/test';
import { requireSession } from '../../fixtures/market-hours';
import { countEmails } from '../../fixtures/mailbox';

// The stack must be pinned to CLOSED for a few minutes before it opens (MARKET_HOURS_FORCE_SESSION=
// CLOSED, MARKET_HOURS_FORCE_MINUTES=5): see "Market hours tests" in e2e/README.md. Runs after
// closed.spec.ts (alphabetical, one worker) and waits for the open, so it takes as long as the
// stack has left.
test.use({ persona: 'trader' });

const SYMBOL = 'AAPL';
/** Longest wait for the open worth sitting through. */
const MAX_WAIT_MS = 15 * 60_000;

test('a market order queued while closed fills at the open, and the client is emailed', async ({ trader, request }) => {
  const status = await requireSession(trader, 'CLOSED');
  // The stack has to be started the right way for this one; otherwise there is nothing to watch.
  // eslint-disable-next-line playwright/no-skipped-test
  test.skip(!status.nextRegularOpen, 'The session is pinned for good; start the stack with MARKET_HOURS_FORCE_MINUTES.');
  const waitMs = Date.parse(status.nextRegularOpen!) - Date.parse(status.now);
  // eslint-disable-next-line playwright/no-skipped-test
  test.skip(waitMs > MAX_WAIT_MS, `The market opens in ${Math.round(waitMs / 60_000)} minutes; start the stack with fewer.`);
  test.setTimeout(waitMs + 180_000);

  const subject = `market buy of 3 ${SYMBOL} filled`;
  const sharesBefore = await trader.api.sharesHeld(trader.accountId, SYMBOL);
  const emailsBefore = await countEmails(request, trader.persona.email, subject);

  // Queue it while the market is closed.
  const placed = await (await trader.api.placeOrderRaw({
    accountId: trader.accountId, symbol: SYMBOL, side: 'BUY', quantity: 3
  })).json();
  expect(placed).toMatchObject({ status: 'OPEN', orderType: 'MARKET' });
  expect(await trader.api.sharesHeld(trader.accountId, SYMBOL)).toBe(sharesBefore);

  // The market opens...
  await expect
    .poll(async () => (await trader.api.marketStatus()).session, { timeout: waitMs + 90_000, intervals: [2_000] })
    .toBe('REGULAR');

  // ...and the queued order fills at the opening price, within its collar.
  await expect
    .poll(async () => (await trader.api.orderHistory()).content.find(o => o.orderId === placed.orderId)?.status, {
      message: 'the queued order should fill once the market opens', timeout: 60_000, intervals: [2_000]
    })
    .toBe('FILLED');
  expect(await trader.api.sharesHeld(trader.accountId, SYMBOL)).toBe(sharesBefore + 3);
  expect((await trader.api.openOrders()).some(o => o.orderId === placed.orderId)).toBe(false);

  // An order that waited tells the client how it ended.
  await expect
    .poll(() => countEmails(request, trader.persona.email, subject), {
      message: 'a fill email in Mailpit', timeout: 60_000, intervals: [3_000]
    })
    .toBeGreaterThan(emailsBefore);
});
