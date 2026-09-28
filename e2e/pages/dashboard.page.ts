import { expect, Locator, Page } from '@playwright/test';
import { parseAmount } from './format';
import { SymbolSearch } from './symbol-search';
import { TradePanel } from './trade-panel';

export type RangeLabel = 'Today' | 'Past week' | 'Past month' | 'Past 3 months' | 'Past year' | 'All time';

export class DashboardPage {
  readonly headline: Locator;
  readonly accountLabel: Locator;
  readonly buyingPower: Locator;
  readonly investments: Locator;
  readonly todaysReturn: Locator;
  readonly totalReturn: Locator;
  readonly chart: Locator;
  readonly chartMessage: Locator;
  readonly ranges: Locator;
  readonly tickerStrip: Locator;
  readonly positions: Locator;
  readonly activity: Locator;
  readonly accountBanner: Locator;
  readonly search: SymbolSearch;
  readonly trade: TradePanel;

  constructor(readonly page: Page) {
    this.headline = page.getByTestId('headline-value');
    this.accountLabel = page.locator('#portfolio-title');
    this.buyingPower = page.getByTestId('stat-buying-power');
    this.investments = page.getByTestId('stat-investments');
    this.todaysReturn = page.getByTestId('stat-todays-return');
    this.totalReturn = page.getByTestId('stat-total-return');
    this.chart = page.getByRole('img', { name: /Portfolio value/ });
    this.chartMessage = page.locator('.chart-message');
    this.ranges = page.getByRole('tablist', { name: 'Chart range' });
    this.tickerStrip = page.getByRole('region', { name: 'Market indices' });
    this.positions = page.getByRole('region', { name: 'Positions' });
    this.activity = page.getByRole('region', { name: 'Recent activity' });
    this.accountBanner = page.locator('.banner[role="alert"]');
    this.search = new SymbolSearch(page);
    this.trade = new TradePanel(page);
  }

  async goto(): Promise<void> {
    await this.page.goto('/dashboard');
    await this.expectLoaded();
  }

  /** Account figures are in: the buying power stat shows money rather than the "—" placeholder. */
  async expectLoaded(): Promise<void> {
    await expect(this.buyingPower).toHaveText(/\$/);
  }

  range(label: RangeLabel): Locator {
    return this.ranges.getByRole('tab', { name: label, exact: true });
  }

  positionRows(): Locator {
    return this.positions.getByRole('listitem');
  }

  positionRow(symbol: string): Locator {
    return this.positionRows().filter({ has: this.page.getByRole('button', { name: `Trade ${symbol},` }) });
  }

  activityRows(): Locator {
    return this.activity.getByRole('listitem');
  }

  async buyingPowerValue(): Promise<number> {
    await this.expectLoaded();
    return parseAmount(await this.buyingPower.textContent())!;
  }
}
