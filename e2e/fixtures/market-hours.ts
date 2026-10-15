import { test } from '@playwright/test';
import { MarketSession, MarketStatus } from './api';
import { Trader } from './test';

/**
 * The market hours specs run against a stack pinned to one session (MARKET_HOURS_FORCE_SESSION), so
 * each spec file only applies to its own. Skips the current test, saying how to start the stack it
 * needs, when the stack is in another session. Returns the status for the tests that go on.
 */
export async function requireSession(trader: Trader, ...sessions: MarketSession[]): Promise<MarketStatus> {
  const status = await trader.api.marketStatus();
  // Skipping is the point: each spec file only applies to the session its stack is pinned to.
  // eslint-disable-next-line playwright/no-skipped-test
  test.skip(
    !sessions.includes(status.session),
    `The stack is in ${status.session}${status.mode === 'ALWAYS_OPEN' ? ' (market hours are off)' : ''}; ` +
      `this needs ${sessions.join(' or ')}. See "Market hours tests" in e2e/README.md.`
  );
  return status;
}

/** A limit price a buy fills at straight away: 2% above the ask, to the cent. */
export function marketableBuyLimit(ask: number): string {
  return (Math.ceil(ask * 1.02 * 100) / 100).toFixed(2);
}
