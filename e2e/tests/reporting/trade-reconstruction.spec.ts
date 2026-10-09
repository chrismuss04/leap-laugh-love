import { readFile } from 'node:fs/promises';
import { test, expect } from '../../fixtures/test';
import { staff } from '../../data/users';

// Trade Reconstruction: Trading Operations find a trade, see its steps and download them.
test.describe('Trade reconstruction', () => {
  test('Trading Operations reconstruct a trade and download it as CSV', async ({ page, trader, tokenFor }) => {
    const { orderId } = await trader.api.placeOrder(
      { accountId: trader.accountId, symbol: 'AAPL', side: 'BUY', quantity: 1 });

    await page.addInitScript(t => localStorage.setItem('auth_token', t), await tokenFor(staff.tradingOps.email));
    await page.goto('/reporting');
    await page.getByTestId('ops-order-id').fill(orderId);
    await page.getByTestId('ops-search').click();
    await expect(page.getByTestId('ops-result')).toContainText(trader.persona.email);

    await page.getByTestId('ops-result').getByRole('button', { name: 'View' }).click();
    const steps = page.getByTestId('ops-step');
    await expect(steps.filter({ hasText: 'SUBMITTED' })).toContainText(trader.accountNumber);
    await expect(steps.filter({ hasText: 'PRICED' })).toContainText('Market quote');
    await expect(steps.filter({ hasText: 'EXECUTED' })).toBeVisible();
    await expect(steps.last()).toContainText('FILLED');

    const [download] = await Promise.all([
      page.waitForEvent('download'),
      page.getByTestId('ops-download').click()
    ]);
    expect(download.suggestedFilename()).toBe(`trade-${orderId}.csv`);
    const csv = await readFile(await download.path(), 'utf8');
    expect(csv).toMatch(/^order_id,time_utc,step,description\r\n/);
    expect(csv).toContain(`${orderId},`);
  });

  test('clients and commercial analysts are refused trade reconstruction', async ({ request, trader, tokenFor }) => {
    for (const token of [trader.token, await tokenFor(staff.analyst.email)]) {
      const response = await request.get('/api/order/ops/orders',
        { headers: { Authorization: `Bearer ${token}` }, params: { symbol: 'AAPL' } });
      expect(response.status()).toBe(403);
    }
  });
});
