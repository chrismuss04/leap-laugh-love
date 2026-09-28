import { test, expect } from '../../fixtures/test';
import { DashboardPage } from '../../pages/dashboard.page';
import { money, MONEY, parseAmount } from '../../pages/format';
import { TradePanel } from '../../pages/trade-panel';

// Every test here trades from this worker's own funded account, so parallel runs never see each
// other's orders. The ledgers are append-only, so assertions compare before and after.
test.use({ persona: 'trader' });

const SYMBOL = 'MSFT';

test.describe('Buying', () => {
  let dashboard: DashboardPage;
  let ticket: TradePanel;

  test.beforeEach(async ({ page }) => {
    dashboard = new DashboardPage(page);
    ticket = dashboard.trade;
    await dashboard.goto();
    await dashboard.search.pick(SYMBOL);
    await ticket.expectPriced();
  });

  test('buying one share fills it and updates the account everywhere @smoke', async ({ trader }) => {
    const sharesBefore = await trader.api.sharesHeld(trader.accountId, SYMBOL);
    const cashBefore = await trader.api.buyingPower(trader.accountId);

    await ticket.quantity.fill('1');
    await expect(ticket.estimate).toHaveText(MONEY);
    await ticket.reviewButton.click();
    await expect(ticket.reviewText).toContainText(`You're placing a market order to buy 1 share of ${SYMBOL}`);
    await ticket.submitButton.click();

    await expect(ticket.result).toContainText(`Bought 1 share of ${SYMBOL}`);
    const totalCost = parseAmount(await ticket.result.locator('dt:text-is("Total cost") + dd').textContent())!;
    const cashAfterShown = parseAmount(await ticket.result.locator('dt:text-is("Buying power") + dd').textContent())!;
    expect(cashAfterShown).toBeCloseTo(cashBefore - totalCost, 2);

    // The backend agrees with what the ticket reported.
    expect(await trader.api.sharesHeld(trader.accountId, SYMBOL)).toBe(sharesBefore + 1);
    expect(await trader.api.buyingPower(trader.accountId)).toBeCloseTo(cashAfterShown, 2);

    // And the rest of the dashboard refreshed on its own.
    await ticket.done.click();
    await expect(dashboard.buyingPower).toHaveText(money(cashAfterShown));
    await expect(dashboard.positionRow(SYMBOL)).toBeVisible();
    await expect(dashboard.activityRows().first()).toContainText(SYMBOL);
    await expect(dashboard.activityRows().first().locator('.status')).toHaveText('Filled');
  });

  test('an order bigger than buying power is blocked before review', async () => {
    await ticket.quantity.fill('10000000');
    await expect(ticket.hint).toHaveText(/^Not enough buying power \(\$[\d,]+\.\d{2} available\)$/);
    await expect(ticket.reviewButton).toBeDisabled();
  });

  test('the order cannot be submitted twice while it is being placed', async ({ page }) => {
    let release!: () => void;
    const held = new Promise<void>(resolve => (release = resolve));
    let submissions = 0;
    await page.route('**/api/order/orders', async route => {
      if (route.request().method() !== 'POST') {
        return route.continue();
      }
      submissions++;
      await held;
      await route.continue();
    });

    await ticket.quantity.fill('1');
    await ticket.reviewButton.click();
    await ticket.submitButton.click();
    await expect(ticket.submitButton).toBeDisabled();

    release();
    await expect(ticket.result).toBeVisible();
    expect(submissions).toBe(1);
  });
});
