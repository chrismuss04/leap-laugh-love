import { Type } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Router, provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { routes } from '../app.module';
import { SessionActivityService } from '../services/session-activity';
import { TradingOpsDashboardComponent } from './trading-ops/trading-ops';
import { OrderLifecycleComponent } from './trading-ops/order-lifecycle';
import { AnalystDashboardComponent } from './analyst/analyst';
import { BusinessInsights } from '../services/reports';

// Staff Dashboards: each role's dashboard, reached through the app's real routes and staff shell.
const ORDER_ID = '3f2b8c1e-9a4d-4c2e-8f1a-6b7d5e0c9a21';

function signInAs(role: 'TRADING_OPERATIONS' | 'COMMERCIAL_ANALYST'): void {
  const now = Math.floor(Date.now() / 1000);
  localStorage.setItem('auth_token', 'header.' + btoa(JSON.stringify({
    role, email: 'staff@leap.com', sid: 'staff-session', iat: now, exp: now + 3600
  })).replace(/=+$/, '') + '.signature');
}

function setUp(): { router: Router; http: HttpTestingController } {
  TestBed.configureTestingModule({
    providers: [provideRouter(routes), provideHttpClient(), provideHttpClientTesting()]
  });
  const activity = TestBed.inject(SessionActivityService);
  vi.spyOn(activity, 'start').mockImplementation(() => undefined);
  vi.spyOn(activity, 'stop').mockImplementation(() => undefined);
  return { router: TestBed.inject(Router), http: TestBed.inject(HttpTestingController) };
}

/** Opens a URL in the app and returns the page the staff shell rendered for it, with its element. */
async function open<T>(url: string, page: Type<T>): Promise<{ harness: RouterTestingHarness; page: T; el: HTMLElement }> {
  const harness = await RouterTestingHarness.create();
  await harness.navigateByUrl(url);
  const found = harness.fixture.debugElement.query(By.directive(page));
  expect(found, `${url} did not render ${page.name}`).not.toBeNull();
  return { harness, page: found.componentInstance, el: found.nativeElement };
}

const text = (el: HTMLElement) => el.textContent!.replace(/\s+/g, ' ').trim();
const panelTitles = (el: HTMLElement) =>
  Array.from(el.querySelectorAll('app-report-panel h2')).map(h => (h as HTMLElement).textContent!.trim());

const INSIGHTS_URL = '/api/order/reports/insights';

function insights(overrides: Partial<BusinessInsights> = {}): BusinessInsights {
  return {
    from: '2026-10-01', to: '2026-10-07', previousFrom: '2026-09-24', previousTo: '2026-09-30',
    current: { tradedValue: 2290, tradeCount: 4, averageOrderValue: 572.5, newRegistrations: 2, activeClients: 3 },
    previous: { tradedValue: 2000, tradeCount: 2, averageOrderValue: 1000, newRegistrations: 0, activeClients: 3 },
    topInstruments: [
      { symbol: 'AAPL', tradeCount: 2, tradedValue: 1600 },
      { symbol: 'MSFT', tradeCount: 1, tradedValue: 600 },
      { symbol: 'NVDA', tradeCount: 1, tradedValue: 90 }
    ],
    ...overrides
  };
}

/** The analyst's headline tiles as shown. */
const tiles = (el: HTMLElement) => Array.from(el.querySelectorAll('[data-testid="insights"] .stat')).map(stat => ({
  label: stat.querySelector('.stat-label')!.textContent,
  value: stat.querySelector('.stat-value')!.textContent,
  hint: stat.querySelector('.stat-hint')?.textContent ?? '',
  trend: stat.querySelector('.stat-trend')?.textContent ?? ''
}));

function type(el: HTMLElement, selector: string, value: string): void {
  const input = el.querySelector(selector) as HTMLInputElement;
  input.value = value;
  input.dispatchEvent(new Event('input'));
}

describe('Trading operations dashboard', () => {
  let router: Router;
  let http: HttpTestingController;

  beforeEach(() => {
    localStorage.clear();
    signInAs('TRADING_OPERATIONS');
    ({ router, http } = setUp());
  });

  afterEach(() => localStorage.clear());

  it('opens at /reporting with a place for every audit report, and loads nothing yet', async () => {
    const { el } = await open('/reporting', TradingOpsDashboardComponent);

    expect(router.url).toBe('/reporting');
    expect(el.querySelector('h1')!.textContent).toContain('Order Audit');
    expect(panelTitles(el)).toEqual(['Order audit log', 'Rejections by reason', 'Stuck orders']);
    expect(el.querySelectorAll('[data-testid="report-pending"]').length).toBe(2);
    expect(el.querySelector('.panel-body')!.textContent).toContain('time period and a client id or email');
    expect(Array.from(el.querySelectorAll('.stat-label')).map(l => l.textContent))
      .toEqual(['Orders submitted', 'Filled', 'Rejected', 'Awaiting execution']);
    expect(text(el.querySelector('[data-testid="filter-summary"]')!)).toBe('Showing all orders.');
    http.expectNone(() => true);
  });

  it('reads the audit filter from the URL', async () => {
    const { page, el } = await open('/reporting?status=REJECTED&symbol=MSFT', TradingOpsDashboardComponent);

    expect(page.filters.value()).toEqual(expect.objectContaining({ status: 'REJECTED', symbol: 'MSFT', clientId: null }));
    expect(text(el.querySelector('[data-testid="filter-summary"]')!))
      .toBe('Showing orders matching 2 filters.');
  });

  it('keeps applied filters in the URL', async () => {
    const { harness, el } = await open('/reporting', TradingOpsDashboardComponent);

    type(el, 'input[formControlName="from"]', '2026-10-01T09:00');
    type(el, 'input[formControlName="to"]', '2026-10-08T17:30');
    type(el, 'input[formControlName="clientEmail"]', 'alice@leap.com');
    type(el, 'input[formControlName="symbol"]', 'aapl');
    el.querySelector('form[aria-label="Audit filters"]')!.dispatchEvent(new Event('submit'));
    await harness.fixture.whenStable();
    harness.detectChanges();

    expect(router.url).toBe('/reporting?from=2026-10-01T09:00&to=2026-10-08T17:30&clientEmail=alice@leap.com&symbol=AAPL');
    expect(text(el.querySelector('[data-testid="filter-summary"]')!)).toBe('Showing orders matching 4 filters.');
    http.expectOne(request => request.url === '/api/order/ops/orders').flush([]);
  });

  it('traces an order, keeping the audit filters for the way back', async () => {
    const { harness, el } = await open('/reporting?symbol=AAPL', TradingOpsDashboardComponent);

    type(el, 'input[formControlName="orderId"]', ORDER_ID);
    el.querySelector('form[aria-label="Trace an order"]')!.dispatchEvent(new Event('submit'));
    await harness.fixture.whenStable();
    harness.detectChanges();

    expect(router.url).toBe(`/reporting/orders/${ORDER_ID}?symbol=AAPL`);
    const trace = harness.fixture.debugElement.query(By.directive(OrderLifecycleComponent)).nativeElement as HTMLElement;
    expect(trace.querySelector('.back-link')!.getAttribute('href')).toBe('/reporting?symbol=AAPL');
  });

  it('retraces an order through every step with a place for each timestamp and ledger', async () => {
    const { el } = await open(`/reporting/orders/${ORDER_ID}`, OrderLifecycleComponent);

    expect(el.querySelector('[data-testid="lifecycle-order-id"]')!.textContent).toContain(ORDER_ID);
    expect(el.querySelectorAll('[data-testid="lifecycle-stage"]').length).toBe(8);
    expect(panelTitles(el)).toEqual(['Order summary', 'Lifecycle timeline', 'Executions', 'Cash entries', 'Position movements']);
    expect(el.querySelector('[role="alert"]')).toBeNull();
    http.expectNone(() => true);
  });

  it('downloads the traced order as CSV', async () => {
    const { harness, el } = await open(`/reporting/orders/${ORDER_ID}`, OrderLifecycleComponent);
    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => undefined);
    vi.spyOn(URL, 'createObjectURL').mockReturnValue('blob:csv');
    vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => undefined);

    (el.querySelector('[data-testid="lifecycle-download"]') as HTMLButtonElement).click();
    http.expectOne(`/api/order/ops/orders/${ORDER_ID}/timeline.csv`).flush(new Blob(['csv']));
    harness.detectChanges();

    expect((click.mock.contexts.at(-1) as HTMLAnchorElement).download).toBe(`trade-${ORDER_ID}.csv`);
    expect(URL.revokeObjectURL).toHaveBeenCalledWith('blob:csv');
    expect(el.querySelector('[role="alert"]')).toBeNull();
  });

  it('says when there is no order to download', async () => {
    const { harness, el } = await open(`/reporting/orders/${ORDER_ID}`, OrderLifecycleComponent);

    (el.querySelector('[data-testid="lifecycle-download"]') as HTMLButtonElement).click();
    http.expectOne(`/api/order/ops/orders/${ORDER_ID}/timeline.csv`)
      .flush(new Blob(['']), { status: 404, statusText: 'Not Found' });
    harness.detectChanges();

    expect(el.querySelector('[role="alert"]')!.textContent).toContain('No order has this id.');
  });

  it('explains when the address does not hold an order id', async () => {
    const { el } = await open('/reporting/orders/not-an-order', OrderLifecycleComponent);

    expect(el.querySelector('[role="alert"]')!.textContent).toContain("isn't an order id");
    expect(el.querySelector('app-lifecycle-timeline')).toBeNull();
    expect(el.querySelector('[data-testid="lifecycle-download"]')).toBeNull();
  });

  it('looks no orders up until the filter has a period and a client', async () => {
    const { el } = await open('/reporting?symbol=AAPL', TradingOpsDashboardComponent);

    expect(el.querySelector('app-trade-reconstruction')).toBeNull();
    expect(el.querySelector('.panel-body')!.textContent).toContain('time period and a client id or email');
    // Nothing is looked up (or logged) until the filter can be searched.
    http.expectNone(() => true);
  });

  it('lists the orders the audit filter finds, each tracing to its lifecycle', async () => {
    const { harness, el } = await open(
      '/reporting?from=2026-10-01T09:00&to=2026-10-08T17:30&clientEmail=alice%40leap.com&status=FILLED',
      TradingOpsDashboardComponent);

    const search = http.expectOne(request => request.url === '/api/order/ops/orders');
    expect(search.request.params.get('clientEmail')).toBe('alice@leap.com');
    expect(search.request.params.get('status')).toBe('FILLED');
    search.flush([{
      orderId: ORDER_ID, submittedAt: '2026-10-02T10:00:00Z', clientEmail: 'alice@leap.com', accountNumber: 'ACC-1001',
      symbol: 'AAPL', side: 'BUY', quantity: 10, status: 'FILLED'
    }]);
    harness.detectChanges();

    const rows = el.querySelectorAll('[data-testid="audit-log-row"]');
    expect(rows.length).toBe(1);
    expect(Array.from(rows[0].querySelectorAll('td')).map(cell => text(cell)))
      .toEqual(['2026-10-02 10:00:00', 'alice@leap.com', 'ACC-1001', 'BUY 10 AAPL', 'Filled', 'Trace']);
    expect(rows[0].querySelector('a')!.getAttribute('href')).toBe(
      `/reporting/orders/${ORDER_ID}?from=2026-10-01T09:00&to=2026-10-08T17:30&clientEmail=alice@leap.com&status=FILLED`);
  });

  it('says when no orders match, and shows why a search failed', async () => {
    const { harness, el } = await open(
      '/reporting?from=2026-10-01T09:00&to=2026-10-08T17:30&clientEmail=nobody%40leap.com', TradingOpsDashboardComponent);

    http.expectOne(request => request.url === '/api/order/ops/orders').flush([]);
    harness.detectChanges();
    expect(el.querySelector('.panel-body')!.textContent).toContain('No orders match these filters.');

    (harness.fixture.debugElement.query(By.directive(TradingOpsDashboardComponent)).componentInstance as TradingOpsDashboardComponent)
      .retrySearch();
    http.expectOne(request => request.url === '/api/order/ops/orders')
      .flush({ message: 'Give a client id or client email to search' }, { status: 400, statusText: 'Bad Request' });
    harness.detectChanges();
    expect(el.querySelector('[role="alert"]')!.textContent).toContain('Give a client id or client email to search');
  });
});

describe('Commercial analyst dashboard', () => {
  let router: Router;
  let http: HttpTestingController;

  beforeEach(() => {
    localStorage.clear();
    signInAs('COMMERCIAL_ANALYST');
    ({ router, http } = setUp());
  });

  afterEach(() => localStorage.clear());

  it('opens at /reporting with the activity report and the six 12-month reports, and loads each once', async () => {
    const { el } = await open('/reporting', AnalystDashboardComponent);

    expect(router.url).toBe('/reporting');
    expect(el.querySelector('h1')!.textContent).toContain('Trading Activity');
    expect(panelTitles(el)).toEqual([
      'Monthly scorecard', 'Retention by sign-up month', 'Client activity', 'Activation',
      'Volume from top clients', 'Orders by value'
    ]);
    expect(text(el.querySelector('#year-heading')!)).toBe('The last 12 months');
    expect(el.querySelectorAll('[data-testid="report-pending"]').length).toBe(0);
    expect(Array.from(el.querySelectorAll('.stat-label')).map(l => l.textContent))
      .toEqual(['Trading volume', 'Average order value', 'New registrations', 'Active clients', 'Most active instrument']);
    expect(text(el.querySelector('[data-testid="period-summary"]')!)).toMatch(/^Past month: /);
    expect(el.querySelector('app-activity-report')).not.toBeNull();
    http.expectOne('/api/order/reports/instruments');
    http.expectOne(request => request.url === '/api/order/reports/activity');
    http.expectOne(request => request.url === INSIGHTS_URL);
    for (const report of ['scorecard', 'client-activity', 'activation', 'retention', 'concentration', 'order-sizes']) {
      http.expectOne(`/api/reporting/analyst/${report}`);
    }
    http.expectNone(() => true);
  });

  it('names the months the 12-month reports cover once, in their heading, when the first report arrives', async () => {
    const { harness, el } = await open('/reporting', AnalystDashboardComponent);
    expect(el.querySelector('[data-testid="report-window"]')).toBeNull();

    http.expectOne('/api/reporting/analyst/scorecard').flush({ window: { firstMonth: '2025-10', lastMonth: '2026-09' }, months: [] });
    harness.fixture.detectChanges();

    expect(text(el.querySelector('[data-testid="report-window"]')!)).toBe('Oct 2025 – Sep 2026');
  });

  it("doesn't reload the 12-month reports when the period changes: they always cover the last 12 months", async () => {
    const { harness, el } = await open('/reporting', AnalystDashboardComponent);
    http.match(request => request.url.startsWith('/api/reporting/analyst/'));
    const tab = Array.from(el.querySelectorAll('[role="tab"]'))
      .find(button => button.textContent!.trim() === 'Past 3 months') as HTMLButtonElement;

    tab.click();
    await harness.fixture.whenStable();

    expect(router.url).toBe('/reporting?period=3M');
    http.expectNone(request => request.url.startsWith('/api/reporting/analyst/'));
  });

  it('fills the headline tiles for the chosen period, with the change since the previous one', async () => {
    const { harness, el } = await open('/reporting?period=CUSTOM&from=2026-10-01&to=2026-10-07', AnalystDashboardComponent);
    expect(tiles(el).map(t => t.value)).toEqual(['—', '—', '—', '—', '—']);

    const request = http.expectOne(r => r.url === INSIGHTS_URL);
    expect(request.request.params.get('from')).toBe('2026-10-01');
    expect(request.request.params.get('to')).toBe('2026-10-07');
    request.flush(insights());
    harness.detectChanges();

    expect(tiles(el)).toEqual([
      { label: 'Trading volume', value: '$2,290.00', hint: 'Value of filled orders', trend: '+14.50% vs previous period' },
      { label: 'Average order value', value: '$572.50', hint: 'Per filled order', trend: '−42.75% vs previous period' },
      { label: 'New registrations', value: '2', hint: 'Clients who signed up', trend: 'No earlier figure to compare' },
      { label: 'Active clients', value: '3', hint: 'Placed at least one order', trend: '0.00% vs previous period' },
      { label: 'Most active instrument', value: 'AAPL', hint: '2 trades', trend: 'Then MSFT, NVDA' }
    ]);
    const trends = Array.from(el.querySelectorAll('.stat-trend'));
    expect(trends[0].classList.contains('text-gain')).toBe(true);
    expect(trends[1].classList.contains('text-loss')).toBe(true);
    expect(el.querySelector('[data-testid="insights"]')!.getAttribute('aria-busy')).toBe('false');
  });

  it('says so in the tiles when nothing traded in the period', async () => {
    const { harness, el } = await open('/reporting', AnalystDashboardComponent);
    const nothing = { tradedValue: 0, tradeCount: 0, averageOrderValue: null, newRegistrations: 0, activeClients: 0 };

    http.expectOne(r => r.url === INSIGHTS_URL).flush(insights({ current: nothing, previous: nothing, topInstruments: [] }));
    harness.detectChanges();

    expect(tiles(el).map(t => t.value)).toEqual(['$0.00', '—', '0', '0', '—']);
    expect(tiles(el)[4].hint).toBe('By number of trades');
  });

  it('reloads the tiles when the period changes', async () => {
    const { harness, el } = await open('/reporting', AnalystDashboardComponent);
    http.expectOne(r => r.url === INSIGHTS_URL).flush(insights());

    (Array.from(el.querySelectorAll('[role="tab"]'))
      .find(button => button.textContent!.trim() === 'Past week') as HTMLButtonElement).click();
    await harness.fixture.whenStable();
    harness.detectChanges();

    expect(tiles(el)[0].value).toBe('—');
    http.expectOne(r => r.url === INSIGHTS_URL);
  });

  it('offers a retry when the tiles fail to load', async () => {
    const { harness, el } = await open('/reporting', AnalystDashboardComponent);

    http.expectOne(r => r.url === INSIGHTS_URL).flush('down', { status: 500, statusText: 'Server Error' });
    harness.detectChanges();
    expect(el.querySelector('.stats-error')!.textContent).toContain("Couldn't load the figures");
    expect(tiles(el)[0].value).toBe('—');

    (el.querySelector('.stats-error button') as HTMLButtonElement).click();
    http.expectOne(r => r.url === INSIGHTS_URL).flush(insights());
    harness.detectChanges();
    expect(el.querySelector('.stats-error')).toBeNull();
    expect(tiles(el)[0].value).toBe('$2,290.00');
  });

  it('keeps the chosen period in the URL, and leaves it out for the default', async () => {
    const { harness, el } = await open('/reporting', AnalystDashboardComponent);
    const tab = (label: string) => Array.from(el.querySelectorAll('[role="tab"]'))
      .find(button => button.textContent!.trim() === label) as HTMLButtonElement;

    tab('Past 3 months').click();
    await harness.fixture.whenStable();
    expect(router.url).toBe('/reporting?period=3M');

    tab('Past month').click();
    await harness.fixture.whenStable();
    expect(router.url).toBe('/reporting');
  });

  it('shows a custom period from the URL', async () => {
    const { page, el } = await open('/reporting?period=CUSTOM&from=2026-01-01&to=2026-03-31', AnalystDashboardComponent);

    expect(page.filters.range()).toEqual({ from: '2026-01-01', to: '2026-03-31' });
    expect(text(el.querySelector('[data-testid="period-summary"]')!))
      .toBe('Custom period: 1 Jan 2026 – 31 Mar 2026');
  });

  it('cannot open an order lifecycle trace', async () => {
    const harness = await RouterTestingHarness.create();
    await harness.navigateByUrl(`/reporting/orders/${ORDER_ID}`);
    expect(router.url).toBe('/reporting');
  });

});

// Regression: signing out leaves the router on /reporting, so the next staff member's sign-in
// redirect is to the URL it's already on - which Angular ignores by default, keeping the previous
// role's dashboard. The sign-in screen re-matches with onSameUrlNavigation: 'reload'.
describe('Switching staff in one browser session', () => {
  const cases = [
    { from: 'TRADING_OPERATIONS', to: 'COMMERCIAL_ANALYST', page: AnalystDashboardComponent },
    { from: 'COMMERCIAL_ANALYST', to: 'TRADING_OPERATIONS', page: TradingOpsDashboardComponent }
  ] as const;

  afterEach(() => localStorage.clear());

  for (const { from, to, page } of cases) {
    it(`gives ${to} their own dashboard after ${from} signed out`, async () => {
      localStorage.clear();
      signInAs(from);
      setUp();
      const harness = await RouterTestingHarness.create();
      await harness.navigateByUrl('/reporting');

      signInAs(to);
      await TestBed.inject(Router).navigateByUrl('/reporting', { onSameUrlNavigation: 'reload' });
      harness.detectChanges();

      expect(harness.fixture.debugElement.query(By.directive(page)), `${to} did not get ${page.name}`).not.toBeNull();
    });
  }
});
