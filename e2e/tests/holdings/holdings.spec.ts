import { test, expect } from '../../fixtures/test';
import { HoldingsPage } from '../../pages/holdings.page';

test.use({ persona: 'trader' });

test.describe('Holdings', () => {
  test('lists each position as account-app reports it @smoke', async ({ page, trader }) => {
    await trader.api.placeOrder({ accountId: trader.accountId, symbol: 'AAPL', side: 'BUY', quantity: 1 });
    const { positions } = await trader.api.positions(trader.accountId);
    const aapl = positions.find(p => p.symbol === 'AAPL')!;

    const holdings = new HoldingsPage(page);
    await holdings.goto();

    await expect(holdings.account(trader.accountNumber).locator('tbody tr')).toHaveCount(positions.length);
    await expect(holdings.position(trader.accountNumber, 'AAPL').getByRole('cell')).toHaveText([
      'AAPL', aapl.instrumentName, 'EQUITY', aapl.quantity.toLocaleString('en-US'), /^\$[\d,]+\.\d{2}$/
    ]);
  });
});
