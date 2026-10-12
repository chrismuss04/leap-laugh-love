import { test, expect } from '@playwright/test';
import { apps, RecoveryStack } from '../../fixtures/recovery-stack';
import type { AccountSummary, Balance, AccountPositions, OrderResponse, OrderHistoryPage } from '../../fixtures/api';

const scenarios = [
  { name: 'app restart', run: (stack: RecoveryStack) => stack.compose('restart', ...apps) },
  { name: 'app replacement', run: (stack: RecoveryStack) => stack.compose('up', '-d', '--no-deps', '--no-build', '--force-recreate', ...apps) },
  { name: 'database restart', run: (stack: RecoveryStack) => stack.compose('restart', 'db') },
  { name: 'database crash', run: async (stack: RecoveryStack) => {
    await stack.compose('kill', '-s', 'SIGKILL', 'db');
    await stack.compose('up', '-d', 'db');
  } },
  { name: 'failed deployment', run: (stack: RecoveryStack) => stack.failedDeploy() }
];

for (const [index, scenario] of scenarios.entries()) {
  // Verify a committed trade's complete records and API-visible balances survive each disruption.
  test(scenario.name, async ({ request }, testInfo) => {
    const stack = new RecoveryStack(request);
    await stack.verifyIsolation();
    await stack.healthy();
    const email = `e2e.trader.${String(index).padStart(2, '0')}@leap.test`;
    let token = await stack.login(email);
    const accounts = await stack.json<AccountSummary[]>('account-app', '/api/account/accounts', token);
    const account = accounts.find(a => a.accountNumber === `ACC-E2E-${String(index).padStart(2, '0')}`);
    expect(account, 'seeded recovery account').toBeDefined();
    const accountId = account!.accountId;

    await stack.freshQuote('MSFT', token);
    const trade = await stack.json<OrderResponse>('order-app', '/api/order/orders', token,
      { accountId, symbol: 'MSFT', side: 'BUY', quantity: 1 });
    expect(trade.status).toBe('FILLED');
    const before = await stack.snapshot(accountId);
    expect(before.orders).toHaveLength(1);
    expect(before.executions).toHaveLength(1);
    expect(before.cash).toHaveLength(2); // Seed funding plus one settlement.
    expect(before.holdings).toHaveLength(1);
    expect(before.movements).toHaveLength(1);
    const balance = await stack.json<Balance>('account-app', '/api/account/balance', token);
    const holdings = await stack.json<AccountPositions>('account-app', `/api/account/accounts/${accountId}/positions`, token);
    await testInfo.attach('before', { body: JSON.stringify(before, null, 2), contentType: 'application/json' });

    await scenario.run(stack);
    await stack.healthy();
    // A fresh login tests record accessibility independently of session expiry during restart.
    token = await stack.login(email);
    const after = await stack.snapshot(accountId);
    await testInfo.attach('after', { body: JSON.stringify(after, null, 2), contentType: 'application/json' });
    expect(after).toEqual(before);
    expect(await stack.json<Balance>('account-app', '/api/account/balance', token)).toEqual(balance);
    expect(await stack.json<AccountPositions>('account-app', `/api/account/accounts/${accountId}/positions`, token)).toEqual(holdings);
    const history = await stack.json<OrderHistoryPage>('order-app', '/api/order/orders/history', token);
    expect(history.content).toEqual(expect.arrayContaining([
      expect.objectContaining({ orderId: trade.orderId, status: 'FILLED', symbol: 'MSFT', quantity: 1 })
    ]));
  });
}
