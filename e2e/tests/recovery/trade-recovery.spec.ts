import { test, expect } from '@playwright/test';
import { RecoveryStack } from '../../fixtures/recovery-stack';
import type { AccountSummary, Balance, AccountPositions, OrderResponse } from '../../fixtures/api';

const scenarios = [
  { name: 'before settlement', mode: 'block', settled: false },
  { name: 'lost settlement response', mode: 'drop', settled: true },
  { name: 'before order completion', mode: 'pass', settled: true }
] as const;

// The fault proxy is shared by every scenario: starting it routes order-app through it, which
// means recreating order-app, and doing that per test (plus once more to remove it) was most of
// this file's run time. Each test still sets its own fault mode, which resets the proxy's
// counters, and its own order-app kill and restart are untouched.
test.afterAll(async ({ playwright }) => {
  const request = await playwright.request.newContext();
  try {
    const stack = new RecoveryStack(request);
    await stack.verifyIsolation();
    await stack.stopProxy();
  } finally {
    await request.dispose();
  }
});

for (const [index, scenario] of scenarios.entries()) {
  // Recover an interrupted trade once, then prove concurrent settlement retries leave it unchanged.
  test(scenario.name, async ({ request }, testInfo) => {
    const stack = new RecoveryStack(request);
    await stack.verifyIsolation();
    try {
      await stack.startProxy();
      const suffix = String(index + 8).padStart(2, '0');
      const email = `e2e.trader.${suffix}@leap.test`;
      let token = await stack.login(email);
      const accounts = await stack.json<AccountSummary[]>('account-app', '/api/account/accounts', token);
      const account = accounts.find(a => a.accountNumber === `ACC-E2E-${suffix}`);
      expect(account).toBeDefined();
      const accountId = account!.accountId;
      const before = await stack.snapshot(accountId);
      expect(before.orders).toHaveLength(0);
      const cashBefore = await stack.json<Balance>('account-app', '/api/account/balance', token);
      // The completion fault is harmless in the blocked/drop cases and guarantees a pending state.
      await stack.blockCompletion(accountId);
      await stack.proxyMode(scenario.mode);
      await expect.poll(async () => {
        const response = await request.get(`${await stack.url('market-data-app')}/api/marketdata/prices/MSFT`,
          { headers: { Authorization: `Bearer ${token}` } });
        return response.status();
      }).toBe(200);

      const trade = await stack.json<OrderResponse>('order-app', '/api/order/orders', token,
        { accountId, symbol: 'MSFT', side: 'BUY', quantity: 1 });
      expect(trade.status).toBe('ACCEPTED');
      expect(trade.orderId).toMatch(/^[a-f0-9-]{36}$/i);
      const interrupted = await stack.snapshot(accountId);
      expect(interrupted.orders).toHaveLength(1);
      expect(interrupted.executions).toHaveLength(1);
      expect(interrupted.cash).toHaveLength(scenario.settled ? 2 : 1);
      expect(interrupted.holdings).toHaveLength(scenario.settled ? 1 : 0);
      expect(interrupted.movements).toHaveLength(0);
      const counts = await stack.proxyMode();
      expect(counts.blocked > 0).toBe(scenario.mode === 'block');
      expect(counts.dropped > 0).toBe(scenario.mode === 'drop');
      await testInfo.attach('interrupted', { body: JSON.stringify(interrupted, null, 2), contentType: 'application/json' });

      // Kill the process holding the original request; only durable records can drive recovery.
      await stack.compose('kill', '-s', 'SIGKILL', 'order-app');
      await stack.proxyMode('pass');
      await stack.allowCompletion();
      await stack.compose('up', '-d', '--no-deps', '--no-build', 'order-app');
      await stack.healthy();
      await expect.poll(async () => stack.sql(`SELECT status FROM trading.orders WHERE order_id='${trade.orderId}'`),
        { timeout: 120_000, intervals: [1000, 2000] }).toBe('FILLED');
      const recovered = await stack.snapshot(accountId);
      expect(recovered.executions).toEqual(interrupted.executions);
      expect(recovered.orders).toHaveLength(1);
      expect(recovered.cash).toHaveLength(2);
      expect(recovered.holdings).toHaveLength(1);
      expect(recovered.movements).toHaveLength(1);

      const execution = recovered.executions[0] as { execution_id: string; fill_price: number; executed_at: string };
      const order = recovered.orders[0] as { instrument_id: string };
      const cost = Math.round(execution.fill_price * 100) / 100;
      token = await stack.login(email);
      const balance = await stack.json<Balance>('account-app', '/api/account/balance', token);
      expect(balance.accounts.find(a => a.accountId === accountId)!.balance)
        .toBeCloseTo(cashBefore.accounts.find(a => a.accountId === accountId)!.balance - cost, 2);
      const holdings = await stack.json<AccountPositions>('account-app', `/api/account/accounts/${accountId}/positions`, token);
      expect(holdings.positions).toEqual(expect.arrayContaining([expect.objectContaining({ symbol: 'MSFT', quantity: 1 })]));

      const replay = { orderId: trade.orderId, executionId: execution.execution_id,
        instrumentId: order.instrument_id, symbol: 'MSFT', side: 'BUY', quantity: 1,
        price: execution.fill_price, executedAt: execution.executed_at };
      await Promise.all([0, 1].map(() => stack.json('account-app',
        `/api/account/internal/accounts/${accountId}/settlement`, token, replay)));
      expect(await stack.snapshot(accountId)).toEqual(recovered);
      await testInfo.attach('recovered', { body: JSON.stringify(recovered, null, 2), contentType: 'application/json' });
    } finally {
      await stack.allowCompletion();
      // Leave the shared proxy passing traffic; the next test's startProxy also restarts
      // order-app if this one failed while it was killed.
      await stack.proxyMode('pass');
    }
  });
}
