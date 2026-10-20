import { expect, Locator, Page } from '@playwright/test';
import { parseAmount } from './format';
import { SymbolSearch } from './symbol-search';

export type DrawHorizon = 'Today' | 'This week' | 'This month';
export type DrawPreset = 'rally' | 'dip' | 'breakout' | 'fade';
export type DrawLevel = 'entry' | 'target' | 'stop';

/**
 * Trading Tools > Draw Your Trade (/tools/draw): the chart drawn on, the trade card it makes, and
 * the trade plan tray and review sheet the trade is placed through.
 */
export class DrawPage {
  readonly search: SymbolSearch;
  readonly surface: Locator;
  readonly nowDot: Locator;
  readonly hint: Locator;
  readonly drawnLine: Locator;
  readonly cone: Locator;
  readonly price: Locator;

  readonly card: Locator;
  readonly badge: Locator;
  readonly sentence: Locator;
  readonly terms: Locator;
  readonly odds: Locator;
  readonly boldness: Locator;
  readonly shares: Locator;
  readonly size: Locator;
  readonly ratio: Locator;
  readonly notice: Locator;
  readonly addToPlan: Locator;
  readonly redraw: Locator;

  readonly trayToggle: Locator;
  readonly trayOrders: Locator;
  readonly reviewPlan: Locator;
  readonly reviewSheet: Locator;
  readonly submit: Locator;
  readonly results: Locator;
  readonly reviewSummary: Locator;

  constructor(readonly page: Page) {
    this.search = new SymbolSearch(page);
    this.surface = page.getByTestId('draw-surface');
    this.nowDot = this.surface.locator('.now-dot');
    this.hint = page.getByTestId('draw-hint');
    this.drawnLine = this.surface.locator('path.drawn');
    this.cone = page.getByTestId('draw-cone');
    this.price = page.getByTestId('draw-price');

    this.card = page.getByTestId('draw-trade');
    this.badge = this.card.locator('.badge');
    this.sentence = page.getByTestId('draw-sentence');
    this.terms = page.getByTestId('draw-terms');
    this.odds = page.getByTestId('draw-odds').locator('app-odds-ring');
    this.boldness = page.getByTestId('draw-boldness');
    this.shares = page.getByRole('slider', { name: 'Shares', exact: true });
    this.size = page.getByTestId('draw-size');
    this.ratio = page.getByTestId('draw-ratio');
    this.notice = page.getByTestId('draw-notice');
    this.addToPlan = page.getByTestId('draw-add');
    this.redraw = this.card.getByRole('button', { name: 'Redraw' });

    this.trayToggle = page.getByTestId('tray-toggle');
    this.trayOrders = page.locator('app-trade-plan-tray li.order');
    this.reviewPlan = page.getByTestId('review-plan');
    this.reviewSheet = page.getByTestId('review-sheet');
    this.submit = page.getByTestId('submit');
    this.results = this.reviewSheet.locator('ol.results > li');
    this.reviewSummary = page.getByTestId('review-summary');
  }

  /** Opens the tool and waits for a chart to draw on. */
  async goto(): Promise<void> {
    await this.page.goto('/tools/draw');
    await expect(this.page.getByRole('heading', { name: 'Draw Your Trade' })).toBeVisible();
    await expect(this.nowDot).toBeVisible();
  }

  /** Draws `symbol`: its chip when there is one, else by search. Waits for its chart. */
  async pick(symbol: string): Promise<void> {
    const chip = this.page.getByRole('radio', { name: symbol, exact: true });
    if (await chip.isVisible()) {
      await chip.click();
    } else {
      await this.search.pick(symbol);
    }
    await expect(chip).toHaveAttribute('aria-checked', 'true');
    await expect(this.surface).toHaveAttribute('aria-label', new RegExp(`^Chart of ${symbol}\\.`));
    await expect(this.nowDot).toBeVisible();
  }

  async horizon(label: DrawHorizon): Promise<void> {
    const button = this.page.getByRole('radio', { name: label });
    await button.click();
    await expect(button).toHaveAttribute('aria-checked', 'true');
    await expect(this.nowDot).toBeVisible();
  }

  async preset(id: DrawPreset): Promise<void> {
    await this.page.locator(`[data-preset="${id}"]`).click();
    await expect(this.card).toBeVisible();
  }

  /**
   * Draws with the mouse from the live dot: `across` pixels to the right and `up` pixels up
   * (negative is down), in small moves, as a hand would.
   */
  async drawLine(across: number, up: number): Promise<void> {
    const dot = await this.settledDot();
    const x = dot.x + dot.width / 2;
    const y = dot.y + dot.height / 2;
    await this.page.mouse.move(x, y);
    await this.page.mouse.down();
    // A gentle curve rather than a ruler line: rise slowly at first, then faster.
    for (let i = 1; i <= 24; i++) {
      const t = i / 24;
      await this.page.mouse.move(x + across * t, y - up * t * t);
    }
    await this.page.mouse.up();
    await expect(this.drawnLine).toBeVisible();
    await expect(this.card).toBeVisible();
  }

  /**
   * The live dot's box once the chart sits still in the middle of the viewport. The app scrolls
   * smoothly (html { scroll-behavior: smooth }), so a box measured while an earlier click's
   * scroll is still gliding would send the mouse to the wrong place; the chart is snapped into view
   * instead, and measured until two readings agree (a price tick only nudges the dot).
   */
  private async settledDot(): Promise<{ x: number; y: number; width: number; height: number }> {
    await this.surface.evaluate(el => el.scrollIntoView({ block: 'center', behavior: 'instant' }));
    let last = (await this.nowDot.boundingBox())!;
    await expect(async () => {
      const box = (await this.nowDot.boundingBox())!;
      const still = Math.abs(box.y - last.y) < 3 && Math.abs(box.x - last.x) < 1;
      last = box;
      expect(still, 'the chart has stopped moving').toBe(true);
    }).toPass({ timeout: 5_000 });
    return last;
  }

  /** A level's price, read off its tag on the chart. */
  async level(level: DrawLevel): Promise<number> {
    const tag = this.surface.locator(`.level-tag[data-level="${level}"]`);
    await expect(tag).toBeVisible();
    return parseAmount(await tag.locator('.num').textContent())!;
  }

  /** Sets the size to its smallest (one share) or largest with the slider's keys. */
  async sharesTo(end: 'min' | 'max'): Promise<number> {
    await this.shares.focus();
    await this.page.keyboard.press(end === 'min' ? 'Home' : 'End');
    return Number(await this.shares.inputValue());
  }

  async openTray(): Promise<void> {
    if ((await this.trayToggle.getAttribute('aria-expanded')) !== 'true') {
      await this.trayToggle.click();
    }
    await expect(this.page.getByTestId('tray-panel')).toBeVisible();
  }

  /** The tray's orders as "BUY 1 AAPL (Draw)", for expect.poll. */
  async trayLines(): Promise<string[]> {
    await this.openTray();
    return Promise.all((await this.trayOrders.all()).map(async order => {
      const side = (await order.locator('.side').textContent())!.trim().toUpperCase();
      const symbol = (await order.locator('.symbol').textContent())!.trim();
      const quantity = await order.locator('input[type="number"]').inputValue();
      const source = (await order.locator('.source').textContent())!.trim();
      return `${side} ${quantity} ${symbol} (${source})`;
    }));
  }

  /** The terms under the tray's only Draw order, e.g. "Take profit $190.00 / stop loss $180.00". */
  trayTerms(): Locator {
    return this.trayOrders.getByTestId('tray-terms');
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
