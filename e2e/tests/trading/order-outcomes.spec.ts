import { Page, Route } from '@playwright/test';
import { test, expect } from '../../fixtures/test';
import { DashboardPage } from '../../pages/dashboard.page';

// Outcomes the real backend won't produce on demand - a fill that is still confirming, a
// rejection at execution, a crash part-way through - played back by intercepting the submit.
test.use({ persona: 'trader', allowedConsoleErrors: [/api\/order\/orders/] });

const SYMBOL = 'AAPL';

function orderBody(overrides: Record<string, unknown>) {
  return {
    orderId: '00000000-0000-0000-0000-000000000001',
    accountId: 'mocked',
    accountNumber: 'ACC-MOCK',
    symbol: SYMBOL,
    side: 'BUY',
    quantity: 2,
    status: 'FILLED',
    submittedAt: new Date().toISOString(),
    filledAt: null,
    rejectionReason: null,
    execution: null,
    accountBalanceAfter: null,
    ...overrides
  };
}

/** Intercepts only the submission POST; order history GETs on the same path pass through. */
async function mockSubmit(page: Page, respond: (route: Route) => Promise<void>) {
  await page.route('**/api/order/orders', async route => {
    if (route.request().method() === 'POST') {
      await respond(route);
    } else {
      await route.continue();
    }
  });
}

async function reviewTwoShares(page: Page): Promise<DashboardPage> {
  const dashboard = new DashboardPage(page);
  await dashboard.goto();
  await dashboard.search.pick(SYMBOL);
  await dashboard.trade.expectPriced();
  await dashboard.trade.quantity.fill('2');
  await dashboard.trade.reviewButton.click();
  return dashboard;
}

test.describe('Order outcomes', () => {
  test('an order rejected at execution explains why', async ({ page }) => {
    await mockSubmit(page, route => route.fulfill({
      json: orderBody({ status: 'REJECTED', rejectionReason: 'Insufficient funds - order rejected' })
    }));
    const { trade } = await reviewTwoShares(page);
    await trade.submitButton.click();

    await expect(trade.result).toContainText('Order not filled');
    await expect(trade.result).toContainText('Insufficient funds - order rejected');
  });

  test('an accepted-but-unfilled order says it is still confirming', async ({ page }) => {
    await mockSubmit(page, route => route.fulfill({ json: orderBody({ status: 'ACCEPTED' }) }));
    const { trade } = await reviewTwoShares(page);
    await trade.submitButton.click();

    await expect(trade.result).toContainText('Order placed, confirming');
    await expect(trade.result).toContainText(`buy 2 shares of ${SYMBOL}`);
  });

  test('a refusal with a message keeps the review open and shows it', async ({ page }) => {
    await mockSubmit(page, route => route.fulfill({
      status: 409,
      json: { error: 'STALE_QUOTE', message: 'Quote for AAPL is stale; please retry' }
    }));
    const { trade } = await reviewTwoShares(page);
    await trade.submitButton.click();

    await expect(trade.submitError).toHaveText('Quote for AAPL is stale; please retry');
    await expect(trade.reviewText).toBeVisible();
    await expect(trade.submitButton).toBeEnabled();
  });

  const failures: [string, (route: Route) => Promise<void>][] = [
    ['a server error', route => route.fulfill({ status: 500, json: { message: 'boom' } })],
    ['a dropped connection', route => route.abort()]
  ];
  for (const [what, respond] of failures) {
    test(`${what} while placing an order warns that it may still go through`, async ({ page }) => {
      await mockSubmit(page, respond);
      const { trade } = await reviewTwoShares(page);

      // The dashboard should re-read the account, since the order may have been booked.
      const reloaded = page.waitForRequest(r => r.url().includes('/api/account/balance'));
      await trade.submitButton.click();

      await expect(trade.unconfirmed).toContainText("We couldn't confirm your order");
      await expect(trade.unconfirmed).toContainText('Check Recent activity in a minute before placing it again');
      await reloaded;

      await trade.done.click();
      await expect(trade.quantity).toHaveValue('');
    });
  }
});
