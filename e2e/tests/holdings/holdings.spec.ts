import { test, expect } from '../../fixtures/test';
import { HoldingsPage } from '../../pages/holdings.page';
import { DashboardPage } from '../../pages/dashboard.page';

test.describe('Holdings', () => {
  test.describe('with positions', () => {
    test.use({ persona: 'trader' });

    test.beforeEach(async ({ trader }) => {
      if ((await trader.api.sharesHeld(trader.accountId, 'AAPL')) === 0) {
        await trader.api.placeOrder({ accountId: trader.accountId, symbol: 'AAPL', side: 'BUY', quantity: 1 });
      }
    });

    test('lists each position with its details @smoke', async ({ page, trader }) => {
      const holdings = new HoldingsPage(page);
      await holdings.goto();

      const account = holdings.account(trader.accountNumber);
      await expect(account.locator('.account-currency')).toHaveText('USD');
      await expect(account.getByRole('columnheader')).toHaveText(['Symbol', 'Name', 'Asset Class', 'Quantity', 'Avg Cost']);

      const { positions } = await trader.api.positions(trader.accountId);
      // The page renders exactly what account-app returns for the account.
      await expect(account.locator('tbody tr')).toHaveCount(positions.length);
      const aapl = positions.find(p => p.symbol === 'AAPL')!;
      await expect(holdings.position(trader.accountNumber, 'AAPL').getByRole('cell')).toHaveText([
        'AAPL', aapl.instrumentName, 'EQUITY', aapl.quantity.toLocaleString('en-US'), /^\$[\d,]+\.\d{2}$/
      ]);
    });

    test('quantities agree with the dashboard', async ({ page, trader }) => {
      const holdings = new HoldingsPage(page);
      await holdings.goto();
      const onHoldings = await holdings.position(trader.accountNumber, 'AAPL').getByRole('cell').nth(3).textContent();

      const dashboard = new DashboardPage(page);
      await dashboard.goto();
      await expect(dashboard.positionRow('AAPL').locator('.sub').first()).toHaveText(new RegExp(`^${onHoldings!.trim()} shares?$`));
    });
  });

  test.describe('with two accounts', () => {
    test.use({ persona: 'multi' });

    test('shows one block per account', async ({ page }) => {
      const holdings = new HoldingsPage(page);
      await holdings.goto();
      await expect(holdings.accounts).toHaveCount(2);
      expect((await holdings.accounts.locator('.account-number').allTextContents()).map(t => t.trim()).sort())
        .toEqual(['ACC-E2E-M1', 'ACC-E2E-M2']);
    });
  });

  test.describe('without a trading account', () => {
    test.use({ persona: 'noAccount' });

    test('says there are no accounts', async ({ page }) => {
      const holdings = new HoldingsPage(page);
      await holdings.goto();
      await expect(holdings.empty).toHaveText('No accounts found');
    });
  });

  test.describe('failure', () => {
    test.use({ persona: 'alice', allowedConsoleErrors: [/api\/account\/accounts/] });

    test('a failed load shows an error', async ({ page }) => {
      await page.route('**/api/account/accounts', route => route.fulfill({ status: 500, json: { message: 'Accounts are unavailable' } }));
      const holdings = new HoldingsPage(page);
      await holdings.goto();
      await expect(holdings.error).toHaveText('Accounts are unavailable');
    });
  });
});
