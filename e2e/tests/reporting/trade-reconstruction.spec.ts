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

  test('Trading Operations see the counts and rejections for their filters, and stuck orders', async ({ page, trader, tokenFor }) => {
    await trader.api.placeOrder({ accountId: trader.accountId, symbol: 'AAPL', side: 'BUY', quantity: 1 });
    // Far beyond any buying power, so it is stored REJECTED with its reason.
    const refused = await trader.api.placeOrderRaw({ accountId: trader.accountId, symbol: 'AAPL', side: 'BUY', quantity: 100_000_000 });
    expect((await refused.json()).status).toBe('REJECTED');

    await page.addInitScript(t => localStorage.setItem('auth_token', t), await tokenFor(staff.tradingOps.email));
    await page.goto('/reporting');
    const filters = page.getByRole('form', { name: 'Audit filters' });
    await filters.locator('input[formControlName="from"]').fill(localMinute(new Date(Date.now() - DAY)));
    await filters.locator('input[formControlName="to"]').fill(localMinute(new Date(Date.now() + DAY)));
    await filters.locator('input[formControlName="clientEmail"]').fill(trader.persona.email);
    await filters.getByRole('button', { name: 'Apply' }).click();

    const tile = (label: string) => page.getByTestId('order-counts')
      .locator('app-stat-tile', { has: page.getByText(label, { exact: true }) }).locator('.stat-value');
    for (const label of ['Orders submitted', 'Filled', 'Rejected']) {
      await expect(tile(label)).toHaveText(/^[1-9][\d,]*$/);
    }
    await expect(tile('Awaiting execution')).toHaveText(/^\d[\d,]*$/);

    const panel = (heading: string) => page.locator('app-report-panel', { has: page.getByRole('heading', { name: heading }) });
    // Every client's rejections in the period, so other tests' may be listed alongside this one.
    await expect(panel('Rejections by reason').getByTestId('rejection-row').filter({ hasText: 'Insufficient funds' }).first())
      .toBeVisible();
    // Nothing here leaves an order waiting, so only check the report loaded: a list or "No data available".
    await expect(panel('Stuck orders').getByTestId('stuck-order-row').first()
      .or(panel('Stuck orders').getByText('No data available'))).toBeVisible();
  });

  test("the trace page shows a rejected order's records, and says when a ledger has none", async ({ page, trader, tokenFor }) => {
    const refused = await (await trader.api.placeOrderRaw(
      { accountId: trader.accountId, symbol: 'AAPL', side: 'BUY', quantity: 100_000_000 })).json();
    expect(refused.status).toBe('REJECTED');

    await page.addInitScript(t => localStorage.setItem('auth_token', t), await tokenFor(staff.tradingOps.email));
    await page.goto(`/reporting/orders/${refused.orderId}`);

    const summary = page.getByTestId('order-summary');
    await expect(summary).toContainText(trader.persona.email);
    await expect(summary).toContainText(trader.accountNumber);
    await expect(summary).toContainText('Rejected');
    await expect(summary).toContainText('Insufficient funds');
    await expect(page.getByTestId('execution-row')).toHaveCount(1);
    await expect(page.getByTestId('execution-row')).toContainText('Insufficient funds');
    for (const heading of ['Cash entries', 'Position movements']) {
      await expect(page.locator('app-report-panel', { has: page.getByRole('heading', { name: heading }) }))
        .toContainText('No data available');
    }
  });

  test('clients and commercial analysts are refused trade reconstruction and its reports', async ({ request, trader, tokenFor }) => {
    const { orderId } = await trader.api.placeOrder(
      { accountId: trader.accountId, symbol: 'AAPL', side: 'BUY', quantity: 1 });
    const period = { from: new Date(Date.now() - DAY).toISOString(), to: new Date(Date.now() + DAY).toISOString() };
    const lookups: [string, Record<string, string>][] = [
      ['/api/order/ops/orders', { symbol: 'AAPL' }],
      ['/api/order/ops/orders/counts', { ...period, clientEmail: trader.persona.email }],
      [`/api/order/ops/orders/${orderId}`, {}],
      ['/api/order/ops/reports/rejections', period],
      ['/api/order/ops/reports/stuck-orders', {}]
    ];
    for (const token of [trader.token, await tokenFor(staff.analyst.email)]) {
      for (const [url, params] of lookups) {
        const response = await request.get(url, { headers: { Authorization: `Bearer ${token}` }, params });
        expect(response.status(), url).toBe(403);
      }
    }
  });
});
