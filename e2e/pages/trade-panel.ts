import { expect, Locator, Page } from '@playwright/test';

/** The buy/sell ticket in the dashboard's right rail. */
export class TradePanel {
  readonly root: Locator;
  readonly title: Locator;
  readonly livePrice: Locator;
  readonly buySide: Locator;
  readonly sellSide: Locator;
  readonly account: Locator;
  readonly quantity: Locator;
  readonly increment: Locator;
  readonly decrement: Locator;
  readonly estimate: Locator;
  readonly hint: Locator;
  readonly sellAll: Locator;
  readonly reviewButton: Locator;
  readonly reviewText: Locator;
  readonly submitButton: Locator;
  readonly editButton: Locator;
  readonly submitError: Locator;
  readonly result: Locator;
  readonly unconfirmed: Locator;
  readonly done: Locator;
  // Order types
  readonly limitPrice: Locator;
  readonly stopPrice: Locator;
  readonly trail: Locator;
  readonly untilCancelled: Locator;
  readonly sentence: Locator;
  readonly hitRate: Locator;
  readonly impact: Locator;
  // Bracket ("Protect this trade")
  readonly bracketToggle: Locator;
  readonly takeProfit: Locator;
  readonly stopLoss: Locator;
  readonly riskReward: Locator;
  // Market hours
  /** The strip under the header outside regular hours: "After-hours · Next open Fri 9:30 AM ET". */
  readonly sessionStrip: Locator;
  /** "After-hours" / "Closed" beside the live price. */
  readonly sessionTag: Locator;
  /** Why the chosen order won't execute right now. */
  readonly sessionNote: Locator;
  /** A queued market order's price collar, in place of price protection. */
  readonly collarRow: Locator;
  readonly extendedHours: Locator;
  // Templates
  readonly templates: Locator;
  readonly saveAsTemplate: Locator;
  readonly templateName: Locator;
  readonly templateMessage: Locator;

  constructor(readonly page: Page) {
    this.root = page.locator('app-trade-panel');
    this.title = this.root.locator('#trade-title');
    this.livePrice = this.root.locator('.live-price');
    this.buySide = this.root.getByRole('radio', { name: 'Buy' });
    this.sellSide = this.root.getByRole('radio', { name: 'Sell' });
    this.account = this.root.getByLabel('Account');
    this.quantity = this.root.getByLabel('Shares');
    this.increment = this.root.getByRole('button', { name: 'One more share' });
    this.decrement = this.root.getByRole('button', { name: 'One fewer share' });
    this.estimate = this.root.locator('.field-row.total .field-value');
    this.hint = this.root.locator('.hint');
    this.sellAll = this.root.getByRole('button', { name: 'Sell all' });
    // "Review queued order" for a market order queued for the open.
    this.reviewButton = this.root.getByRole('button', { name: /^Review (queued )?order$/ });
    this.reviewText = this.root.locator('.review-text');
    this.submitButton = this.root.locator('.review .cta');
    this.editButton = this.root.getByRole('button', { name: 'Edit order' });
    this.submitError = this.root.locator('.review .error');
    this.result = page.getByTestId('trade-result');
    this.unconfirmed = page.getByTestId('trade-unconfirmed');
    this.done = this.root.getByRole('button', { name: 'Done' });
    this.limitPrice = this.root.locator('#trade-limit');
    this.stopPrice = this.root.locator('#trade-stop');
    this.trail = this.root.locator('#trade-trail');
    this.untilCancelled = this.root.getByRole('radio', { name: 'Until cancelled' });
    this.sentence = page.getByTestId('order-sentence');
    this.hitRate = page.getByTestId('hit-rate');
    this.impact = page.getByTestId('order-impact');
    this.bracketToggle = this.root.getByLabel('Protect this trade');
    this.takeProfit = this.root.locator('#trade-take-profit');
    this.stopLoss = this.root.locator('#trade-stop-loss');
    this.riskReward = page.getByTestId('risk-reward');
    this.sessionStrip = page.getByTestId('session-strip');
    this.sessionTag = page.getByTestId('session-tag');
    this.sessionNote = page.getByTestId('session-note');
    this.collarRow = page.getByTestId('collar-row');
    this.extendedHours = this.root.getByRole('switch', { name: /Extended hours/ });
    this.templates = page.getByTestId('templates');
    this.saveAsTemplate = this.root.getByRole('button', { name: 'Save as template' });
    this.templateName = this.root.getByLabel('Template name');
    this.templateMessage = page.getByTestId('save-template').locator('.template-message');
  }

  /** Picks an order type: Market, Limit, Stop, Stop limit or Trailing stop. */
  async chooseType(label: 'Market' | 'Limit' | 'Stop' | 'Stop limit' | 'Trailing stop'): Promise<void> {
    await this.root.getByRole('radio', { name: label, exact: true }).click();
  }

  /** Waits until the ticket has a live price for its symbol, so estimates can be checked. */
  async expectPriced(): Promise<void> {
    await expect(this.livePrice).toHaveText(/\$/);
  }

  /** Enter shares, review, submit - and wait for the outcome, whatever it is. */
  async place(quantity: number): Promise<void> {
    await this.expectPriced();
    await this.quantity.fill(String(quantity));
    await this.reviewButton.click();
    await this.submitButton.click();
    await expect(this.result.or(this.unconfirmed).or(this.submitError)).toBeVisible();
  }
}
