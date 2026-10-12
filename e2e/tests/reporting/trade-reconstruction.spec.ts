import { readFile } from 'node:fs/promises';
import { test, expect } from '../../fixtures/test';
import { staff } from '../../data/users';

// A datetime-local value (yyyy-mm-ddThh:mm) in local time, as the audit filter takes it.
const localMinute = (date: Date) => new Date(date.getTime() - date.getTimezoneOffset() * 60_000).toISOString().slice(0, 16);
const DAY = 86_400_000;

// Trade Reconstruction: Trading Operations find a trade with the audit filter, trace it, and download
// the steps order-app rebuilds for it as CSV.
test.describe('Trade reconstruction', () => {
  test('Trading Operations find a trade in the order audit log, trace it and download it as CSV', async ({ page, request, trader, tokenFor }) => {
    const { orderId } = await trader.api.placeOrder(
      { accountId: trader.accountId, symbol: 'AAPL', side: 'BUY', quantity: 1 });
    const token = await tokenFor(staff.tradingOps.email);

    await page.addInitScript(t => localStorage.setItem('auth_token', t), token);
    await page.goto('/reporting');
    // The period and client are required; a day either side covers any browser time zone offset.
    const filters = page.getByRole('form', { name: 'Audit filters' });
    await filters.locator('input[formControlName="from"]').fill(localMinute(new Date(Date.now() - DAY)));
    await filters.locator('input[formControlName="to"]').fill(localMinute(new Date(Date.now() + DAY)));
    await filters.locator('input[formControlName="clientEmail"]').fill(trader.persona.email);
    await filters.locator('input[formControlName="symbol"]').fill('AAPL');
    await filters.getByRole('button', { name: 'Apply' }).click();
    // Newest first, so the order just placed is the first row.
    const row = page.getByTestId('audit-log-row').first();
    await expect(row).toContainText(trader.persona.email);
    await expect(row).toContainText(trader.accountNumber);

    await row.getByRole('link', { name: 'Trace' }).click();
    await expect(page.getByTestId('lifecycle-order-id')).toContainText(orderId);

    const headers = { Authorization: `Bearer ${token}` };
    const timeline = await (await request.get(`/api/order/ops/orders/${orderId}/timeline`, { headers })).json();
    const steps = timeline.steps as { type: string; description: string }[];
    expect(steps.find(step => step.type === 'SUBMITTED')?.description).toContain(trader.accountNumber);
    expect(steps.find(step => step.type === 'PRICED')?.description).toContain('Market quote');
    expect(steps.some(step => step.type === 'EXECUTED')).toBe(true);
    expect(steps.at(-1)?.type).toBe('FILLED');

    const [download] = await Promise.all([
      page.waitForEvent('download'),
      page.getByTestId('lifecycle-download').click()
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
