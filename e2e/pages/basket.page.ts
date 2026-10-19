import { expect, Locator, Page } from '@playwright/test';
import { parseAmount } from './format';
import { SymbolSearch } from './symbol-search';

/** One stock's buy as its basket row shows it: "3 shares at $105.00 · $18.34 left". */
export interface RowBuy {
  symbol: string;
  shares: number;
  price: number;
}

/**
 * Trading Tools > Basket Builder (/tools/basket), with the trade plan tray and review sheet a
 * basket is placed through.
 */
export class BasketPage {
  readonly search: SymbolSearch;
  readonly rows: Locator;
  readonly amount: Locator;
  readonly slider: Locator;
  readonly max: Locator;
  readonly amountNote: Locator;
  readonly legend: Locator;
  readonly totals: Locator;
  readonly leftOut: Locator;
  readonly addToPlan: Locator;
  readonly leftoverPrompt: Locator;
  readonly useLeftovers: Locator;
  readonly addWithout: Locator;
  readonly addedNote: Locator;

  readonly trayToggle: Locator;
  readonly trayOrders: Locator;
  readonly reviewPlan: Locator;
  readonly reviewSheet: Locator;
  readonly submit: Locator;
  readonly results: Locator;
  readonly reviewSummary: Locator;

  constructor(readonly page: Page) {
    this.search = new SymbolSearch(page);
    this.rows = page.locator('app-basket-tool .rows > li');
    this.amount = page.getByTestId('basket-amount');
    this.slider = page.getByTestId('basket-slider');
    this.max = page.getByTestId('basket-max');
    this.amountNote = page.getByTestId('amount-note');
    this.legend = page.getByTestId('split-legend').locator('li');
    this.totals = page.getByTestId('basket-totals');
    this.leftOut = page.getByTestId('left-out');
    this.addToPlan = page.getByTestId('basket-add');
    this.leftoverPrompt = page.getByTestId('leftover-prompt');
    this.useLeftovers = page.getByTestId('use-leftovers');
    this.addWithout = page.getByTestId('add-without');
    this.addedNote = page.getByTestId('added-note');

    this.trayToggle = page.getByTestId('tray-toggle');
    this.trayOrders = page.locator('app-trade-plan-tray li.order');
    this.reviewPlan = page.getByTestId('review-plan');
    this.reviewSheet = page.getByTestId('review-sheet');
    this.submit = page.getByTestId('submit');
    this.results = this.reviewSheet.locator('ol.results > li');
    this.reviewSummary = page.getByTestId('review-summary');
  }

  async goto(): Promise<void> {
    await this.page.goto('/tools/basket');
    await expect(this.page.getByRole('heading', { name: 'Basket Builder' })).toBeVisible();
    await expect(this.page.locator('app-basket-tool .mover').first()).toBeVisible();
  }

  row(symbol: string): Locator {
    return this.rows.and(this.page.locator(`[data-symbol="${symbol}"]`));
  }

  rowAmount(symbol: string): Locator {
    return this.row(symbol).getByTestId('row-amount');
  }

  /** A mover that can still go into the basket, other than the ones named. */
  availableMover(except: string[] = []): Locator {
    let movers = this.page.locator('app-basket-tool .mover[draggable="true"]');
    for (const symbol of except) {
      movers = movers.and(this.page.locator(`:not([data-symbol="${symbol}"])`));
    }
    return movers.first();
  }

  async moverSymbol(mover: Locator): Promise<string> {
    return (await mover.getAttribute('data-symbol'))!;
  }

  /** Adds a stock by the basket's own search. */
  async addBySearch(symbol: string): Promise<void> {
    await this.search.pick(symbol);
    await expect(this.row(symbol)).toBeVisible();
  }

  /** Adds a mover with its Add button; returns its symbol. */
  async addMover(except: string[] = []): Promise<string> {
    const mover = this.availableMover(except);
    const symbol = await this.moverSymbol(mover);
    await mover.getByRole('button', { name: `Add ${symbol} to basket` }).click();
    await expect(this.row(symbol)).toBeVisible();
    return symbol;
  }

  /** Drags a mover onto the basket; returns its symbol. */
  async dragMover(except: string[] = []): Promise<string> {
    const mover = this.availableMover(except);
    const symbol = await this.moverSymbol(mover);
    await mover.dragTo(this.page.getByTestId('basket-drop'));
    await expect(this.row(symbol)).toBeVisible();
    return symbol;
  }

  /** Types the amount to invest and commits it, as leaving the field does. */
  async setAmount(dollars: string): Promise<void> {
    await this.amount.fill(dollars);
    await this.amount.press('Enter');
    await this.amount.blur();
  }

  async setRowAmount(symbol: string, dollars: string): Promise<void> {
    const input = this.rowAmount(symbol);
    await input.fill(dollars);
    await input.press('Enter');
    await input.blur();
  }

  /** What each row says it buys. Rows still waiting for a price are left out. */
  async rowBuys(): Promise<RowBuy[]> {
    const buys: RowBuy[] = [];
    for (const row of await this.rows.all()) {
      const symbol = (await row.getAttribute('data-symbol'))!;
      const text = (await row.getByTestId('row-shares').textContent())!.trim();
      const match = /^([\d,]+) shares? at (\$[\d,]+\.\d{2})/.exec(text);
      if (match) {
        buys.push({ symbol, shares: Number(match[1].replace(/,/g, '')), price: parseAmount(match[2])! });
      }
    }
    return buys;
  }

  /** The amount each row is given, in dollars, in basket order. */
  async rowAmounts(): Promise<number[]> {
    return Promise.all((await this.rows.all()).map(async row => parseAmount(await row.getByTestId('row-amount').inputValue())!));
  }

  async maxAmount(): Promise<number> {
    return parseAmount((await this.max.textContent())!.replace('Up to', ''))!;
  }

  /** Clicks Add to plan and answers the leftovers prompt if it comes up. */
  async addToPlanAnswering(useLeftovers: boolean): Promise<boolean> {
    await this.addToPlan.click();
    // The prompt appears straight away or not at all; the added note means it didn't.
    await expect(this.leftoverPrompt.or(this.addedNote)).toBeVisible();
    if (await this.leftoverPrompt.isVisible()) {
      await (useLeftovers ? this.useLeftovers : this.addWithout).click();
      await expect(this.addedNote).toBeVisible();
      return true;
    }
    return false;
  }

  async openTray(): Promise<void> {
    if ((await this.trayToggle.getAttribute('aria-expanded')) !== 'true') {
      await this.trayToggle.click();
    }
  }

  /**
   * The tray's orders as "BUY 3 AAPL (Basket)". A snapshot: the panel renders a moment after the
   * toggle, so assert on it with expect.poll.
   */
  async trayLines(): Promise<string[]> {
    await this.openTray();
    await expect(this.page.getByTestId('tray-panel')).toBeVisible();
    return Promise.all((await this.trayOrders.all()).map(async order => {
      const side = (await order.locator('.side').textContent())!.trim().toUpperCase();
      const symbol = (await order.locator('.symbol').textContent())!.trim();
      const quantity = await order.locator('input[type="number"]').inputValue();
      const source = (await order.locator('.source').textContent())!.trim();
      return `${side} ${quantity} ${symbol} (${source})`;
    }));
  }

  /** Opens the review sheet from the tray and submits every order, waiting for them to finish. */
  async reviewAndSubmit(): Promise<void> {
    await this.openTray();
    await this.reviewPlan.click();
    await expect(this.reviewSheet).toBeVisible();
    await this.submit.click();
    await expect(this.reviewSummary).toBeVisible({ timeout: 30_000 });
  }
}
