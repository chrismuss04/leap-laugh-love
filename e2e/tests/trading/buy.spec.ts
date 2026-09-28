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
    await expect(ticket.root.locator('.summary')).toContainText(trader.accountNumber);
    await ticket.submitButton.click();

    await expect(ticket.result).toContainText(`Bought 1 share of ${SYMBOL}`);
    const fillPrice = parseAmount(await ticket.result.locator('dt:text-is("Fill price") + dd').textContent())!;
    const totalCost = parseAmount(await ticket.result.locator('dt:text-is("Total cost") + dd').textContent())!;
    const cashAfterShown = parseAmount(await ticket.result.locator('dt:text-is("Buying power") + dd').textContent())!;
    expect(totalCost).toBeCloseTo(fillPrice, 2);
    expect(cashAfterShown).toBeCloseTo(cashBefore - totalCost, 2);

    // The backend agrees with what the ticket reported.
    expect(await trader.api.sharesHeld(trader.accountId, SYMBOL)).toBe(sharesBefore + 1);
    expect(await trader.api.buyingPower(trader.accountId)).toBeCloseTo(cashAfterShown, 2);

    // And the rest of the dashboard refreshed on its own.
    await ticket.done.click();
    await expect(ticket.quantity).toHaveValue('');
    await expect(dashboard.buyingPower).toHaveText(money(cashAfterShown));
    await expect(dashboard.positionRow(SYMBOL)).toBeVisible();
    const latest = dashboard.activityRows().first();
    await expect(latest).toContainText(SYMBOL);
    await expect(latest).toContainText('Buy 1');
    await expect(latest.locator('.status')).toHaveText('Filled');
  });

  test('the estimate is the live price times the shares', async () => {
    await ticket.quantity.fill('3');
    await expect(async () => {
      const price = parseAmount(await ticket.root.locator('.field-row').filter({ hasText: 'Market price' }).locator('.field-value').textContent())!;
      const estimate = parseAmount(await ticket.estimate.textContent())!;
      // Read in one pass, but a tick can land between the two reads - retry until consistent.
      expect(estimate).toBeCloseTo(price * 3, 2);
    }).toPass({ timeout: 10_000 });
  });

  test('the stepper adds and removes whole shares and never goes below one', async () => {
    await ticket.increment.click();
    await expect(ticket.quantity).toHaveValue('1');
    await ticket.increment.click();
    await ticket.increment.click();
    await expect(ticket.quantity).toHaveValue('3');
    await ticket.decrement.click();
    await expect(ticket.quantity).toHaveValue('2');
    await ticket.decrement.click();
    await ticket.decrement.click();
    await expect(ticket.quantity).toHaveValue('1');
  });

  for (const bad of ['0', '-3', '1.5']) {
    test(`"${bad}" shares cannot be reviewed`, async () => {
      await ticket.quantity.fill(bad);
      await expect(ticket.hint).toHaveText('Enter a whole number of shares');
      await expect(ticket.hint).toHaveClass(/error/);
      await expect(ticket.reviewButton).toBeDisabled();
    });
  }

  test('an empty quantity shows buying power, not an error', async () => {
    await ticket.quantity.fill('');
    await expect(ticket.hint).toHaveText(/\$[\d,]+\.\d{2} buying power available/);
    await expect(ticket.reviewButton).toBeDisabled();
  });

  test('an order bigger than buying power is blocked before review', async () => {
    await ticket.quantity.fill('10000000');
    await expect(ticket.hint).toHaveText(/^Not enough buying power \(\$[\d,]+\.\d{2} available\)$/);
    await expect(ticket.reviewButton).toBeDisabled();
  });

  test('"Edit order" goes back to the ticket with the quantity kept', async () => {
    await ticket.quantity.fill('2');
    await ticket.reviewButton.click();
    await expect(ticket.reviewText).toBeVisible();

    await ticket.editButton.click();
    await expect(ticket.quantity).toHaveValue('2');
    await expect(ticket.quantity).toBeFocused();
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
    await expect(ticket.editButton).toBeDisabled();

    release();
    await expect(ticket.result).toBeVisible();
    expect(submissions).toBe(1);
  });

  test('switching symbols resets the ticket', async () => {
    await ticket.quantity.fill('4');
    await dashboard.search.pick('AAPL');

    await expect(ticket.title).toHaveText('Buy AAPL');
    await expect(ticket.quantity).toHaveValue('');
  });
});
