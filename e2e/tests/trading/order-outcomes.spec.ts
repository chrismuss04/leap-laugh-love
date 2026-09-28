import { Page, Route } from '@playwright/test';
import { test, expect } from '../../fixtures/test';
import { DashboardPage } from '../../pages/dashboard.page';

// Outcomes the real backend won't produce on demand - a rejection at execution, a refusal, a
// crash part-way through - played back by intercepting the submit.
test.use({ persona: 'trader', allowedConsoleErrors: [/api\/order\/orders/] });

const SYMBOL = 'AAPL';

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
      json: {
        orderId: '00000000-0000-0000-0000-000000000001', accountId: 'mocked', accountNumber: 'ACC-MOCK',
        symbol: SYMBOL, side: 'BUY', quantity: 2, status: 'REJECTED', submittedAt: new Date().toISOString(),
        filledAt: null, rejectionReason: 'Insufficient funds - order rejected', execution: null, accountBalanceAfter: null
      }
    }));
    const { trade } = await reviewTwoShares(page);
    await trade.submitButton.click();

    await expect(trade.result).toContainText('Order not filled');
    await expect(trade.result).toContainText('Insufficient funds - order rejected');
  });

  test('a refusal keeps the review open and shows the reason', async ({ page }) => {
    await mockSubmit(page, route => route.fulfill({
      status: 409,
      json: { error: 'STALE_QUOTE', message: 'Quote for AAPL is stale; please retry' }
    }));
    const { trade } = await reviewTwoShares(page);
    await trade.submitButton.click();

    await expect(trade.submitError).toHaveText('Quote for AAPL is stale; please retry');
    await expect(trade.submitButton).toBeEnabled();
  });

  test('a server error while placing an order warns that it may still go through', async ({ page }) => {
    // The order may have been booked, so the ticket must not invite a second (duplicate) submit.
    await mockSubmit(page, route => route.fulfill({ status: 500, json: { message: 'boom' } }));
    const { trade } = await reviewTwoShares(page);
    await trade.submitButton.click();

    await expect(trade.unconfirmed).toContainText("We couldn't confirm your order");
    await expect(trade.submitButton).toBeHidden();
  });
});
