import { test, expect } from '../../fixtures/test';
import { BasketPage, RowBuy } from '../../pages/basket.page';
import { money, parseAmount } from '../../pages/format';

// Basket Builder: collect stocks, split an amount across them in whole shares, and place them
// through the trade plan. Every test trades from this worker's own funded account; ledgers are
// append-only, so assertions compare before and after. Prices are live, so expected quantities
// come from what the page shows, never from fixed prices.
test.use({ persona: 'trader' });

/** The tray lines a set of row buys should become: one Basket buy per stock with shares. */
const expectedLines = (buys: RowBuy[]) =>
  buys.filter(b => b.shares > 0).map(b => `BUY ${b.shares} ${b.symbol} (Basket)`).sort();

const sum = (values: number[]) => values.reduce((a, b) => a + b, 0);

test.describe('Basket Builder', () => {
  let basket: BasketPage;

  test.beforeEach(async ({ page }) => {
    basket = new BasketPage(page);
    await basket.goto();
  });

  test('a basket built by search, Add and drag is bought in whole shares @smoke', async ({ trader }) => {
    await basket.addBySearch('AAPL');
    const added = await basket.addMover(['AAPL']);
    const dragged = await basket.dragMover(['AAPL', added]);
    const symbols = ['AAPL', added, dragged];

    await basket.setAmount('2000');
    await expect(basket.legend).toHaveCount(3);
    expect(sum(await basket.rowAmounts())).toBeCloseTo(2000, 2);

    const buys = await basket.rowBuys();
    const bought = buys.filter(b => b.shares > 0);
    expect(bought.length, 'at least one stock gets a share from $2,000').toBeGreaterThan(0);
    const sharesBefore = await Promise.all(symbols.map(s => trader.api.sharesHeld(trader.accountId, s)));
    const cashBefore = await trader.api.buyingPower(trader.accountId);

    await basket.addToPlanAnswering(false);
    expect((await basket.trayLines()).sort()).toEqual(expectedLines(buys));

    await basket.reviewAndSubmit();
    await expect(basket.results).toHaveCount(bought.length);
    for (const result of await basket.results.all()) {
      await expect(result).toHaveAttribute('data-status', 'filled');
    }
    await expect(basket.reviewSummary).toContainText(`${bought.length} filled`);

    // The backend holds exactly the shares the rows promised, and paid about what they cost.
    const sharesAfter = await Promise.all(symbols.map(s => trader.api.sharesHeld(trader.accountId, s)));
    symbols.forEach((symbol, i) => {
      const promised = buys.find(b => b.symbol === symbol)?.shares ?? 0;
      expect(sharesAfter[i] - sharesBefore[i], `${symbol} shares`).toBe(promised);
    });
    const spent = cashBefore - (await trader.api.buyingPower(trader.accountId));
    const quoted = sum(bought.map(b => b.shares * b.price));
    expect(spent).toBeGreaterThan(0);
    expect(Math.abs(spent - quoted) / quoted, 'fills within 2% of the prices shown').toBeLessThan(0.02);
  });

  test('a custom split is bought as split, not evenly', async ({ trader }) => {
    for (const symbol of ['AAPL', 'MSFT', 'AMZN']) {
      await basket.addBySearch(symbol);
    }
    await basket.setAmount('3000');

    // Typing one amount spreads the difference over the others in proportion.
    await basket.setRowAmount('AAPL', '1500');
    expect(await basket.rowAmounts()).toEqual([1500, 750, 750]);

    // Each arrow key moves 1% of the total across the divider: $30 from AMZN to MSFT.
    const divider = basket.page.getByRole('slider', { name: 'Divider between MSFT and AMZN' });
    await divider.focus();
    for (let i = 0; i < 5; i++) {
      await basket.page.keyboard.press('ArrowRight');
    }
    expect(await basket.rowAmounts()).toEqual([1500, 900, 600]);
    await expect(divider).toHaveAttribute('aria-valuetext', 'MSFT 30%, AMZN 20%');
    await expect(basket.legend.filter({ hasText: 'AAPL' })).toContainText('50%');

    const buys = await basket.rowBuys();
    const before = await Promise.all(buys.map(b => trader.api.sharesHeld(trader.accountId, b.symbol)));
    await basket.addToPlanAnswering(false);
    expect((await basket.trayLines()).sort()).toEqual(expectedLines(buys));
    await basket.reviewAndSubmit();

    const after = await Promise.all(buys.map(b => trader.api.sharesHeld(trader.accountId, b.symbol)));
    buys.forEach((b, i) => expect(after[i] - before[i], `${b.symbol} shares`).toBe(b.shares));
  });

  test('an amount over the cash available is brought down to it, keeping the buffer', async ({ page, trader }) => {
    // Only this browser is told the account has $3,000; nothing is placed.
    await page.route('**/api/account/balance', async route => {
      const response = await route.fetch();
      const body = await response.json();
      body.accounts = body.accounts.map((a: { accountId: string; balance: number }) =>
        (a.accountId === trader.accountId ? { ...a, balance: 3000 } : a));
      await route.fulfill({ response, json: body });
    });
    await basket.goto();
    await basket.addBySearch('AAPL');
    await basket.addBySearch('MSFT');

    // $3,000 less the 0.5% the order engine keeps on buys: $2,985.07.
    await expect(basket.max).toHaveText(`Up to ${money(2985.07)}`);
    await expect(basket.slider).toHaveAttribute('max', '298507');

    await basket.setAmount('10000');
    await expect(basket.amount).toHaveValue('2985.07');
    await expect(basket.amountNote).toHaveText(`You can invest up to ${money(2985.07)}.`);
    expect(sum(await basket.rowAmounts())).toBeCloseTo(2985.07, 2);
  });

  test('a stock given less than one share is left out of the plan', async ({ trader }) => {
    await basket.addBySearch('AAPL');
    await basket.addBySearch('MSFT');
    await basket.setAmount('1000');
    await basket.setRowAmount('MSFT', '5');

    await expect(basket.row('MSFT').getByTestId('row-not-enough')).toHaveText('Not enough for 1 share');
    await expect(basket.leftOut).toContainText('MSFT');

    const buys = await basket.rowBuys();
    const aapl = buys.find(b => b.symbol === 'AAPL')!;
    const before = { aapl: await trader.api.sharesHeld(trader.accountId, 'AAPL'), msft: await trader.api.sharesHeld(trader.accountId, 'MSFT') };

    await basket.addToPlanAnswering(false);
    await expect(basket.addedNote).toContainText('Left out, not enough for 1 share: MSFT.');
    expect(await basket.trayLines()).toEqual([`BUY ${aapl.shares} AAPL (Basket)`]);
    await basket.reviewAndSubmit();

    expect(await trader.api.sharesHeld(trader.accountId, 'AAPL')).toBe(before.aapl + aapl.shares);
    expect(await trader.api.sharesHeld(trader.accountId, 'MSFT')).toBe(before.msft);
  });

  test('leftovers buy extra shares, never more than the amount chosen', async ({ trader }) => {
    await basket.addBySearch('AAPL');
    await basket.addBySearch('MSFT');
    // Any amount, just to read each stock's ask from its row.
    await basket.setAmount('1000');
    const asks = Object.fromEntries((await basket.rowBuys()).map(b => [b.symbol, b.price]));

    // 1.6 shares' worth of each: one share apiece, and 0.6 of each left over, which always
    // covers a share of the cheaper one.
    const aaplAmount = Math.ceil(asks['AAPL'] * 1.6 * 100) / 100;
    const total = Math.ceil((aaplAmount + asks['MSFT'] * 1.6) * 100) / 100;
    await basket.setAmount(total.toFixed(2));
    await basket.setRowAmount('AAPL', aaplAmount.toFixed(2));
    const plain = await basket.rowBuys();

    const cashBefore = await trader.api.buyingPower(trader.accountId);
    expect(await basket.addToPlanAnswering(true), 'the leftovers prompt came up').toBe(true);

    const note = (await basket.addedNote.textContent())!;
    const invested = parseAmount(/: (\$[\d,]+\.\d{2}) invested/.exec(note)![1])!;
    expect(invested).toBeLessThanOrEqual(total);

    // More shares than the plain rounding, in the tray as the client's Basket buys.
    const lines = await basket.trayLines();
    const quantity = (symbol: string) => Number(lines.find(l => l.includes(` ${symbol} `))?.split(' ')[1] ?? 0);
    expect(quantity('AAPL') + quantity('MSFT')).toBeGreaterThan(sum(plain.map(b => b.shares)));

    await basket.reviewAndSubmit();
    const spent = cashBefore - (await trader.api.buyingPower(trader.accountId));
    // Fills can drift from the quote a little; never anywhere near more of the account's cash.
    expect(spent).toBeLessThanOrEqual(total * 1.02);
  });

  test('adding again replaces the earlier Basket buys', async () => {
    await basket.addBySearch('AAPL');
    await basket.addBySearch('MSFT');

    await basket.setAmount('1000');
    await basket.addToPlanAnswering(false);
    await basket.setAmount('2500');
    const buys = await basket.rowBuys();
    await basket.addToPlanAnswering(false);

    expect((await basket.trayLines()).sort()).toEqual(expectedLines(buys));
  });

  test('the basket, its amount and its split survive a refresh', async ({ page }) => {
    for (const symbol of ['AAPL', 'MSFT', 'AMZN']) {
      await basket.addBySearch(symbol);
    }
    await basket.setAmount('1500');
    await basket.setRowAmount('AAPL', '900');
    expect(await basket.rowAmounts()).toEqual([900, 300, 300]);

    await page.reload();
    await expect(basket.rows).toHaveCount(3);
    await expect(basket.amount).toHaveValue('1500.00');
    expect(await basket.rowAmounts()).toEqual([900, 300, 300]);
  });
});
