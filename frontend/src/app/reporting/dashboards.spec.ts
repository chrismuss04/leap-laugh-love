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
  spyOn(activity, 'start');
  spyOn(activity, 'stop');
  return { router: TestBed.inject(Router), http: TestBed.inject(HttpTestingController) };
}

/** Opens a URL in the app and returns the page the staff shell rendered for it, with its element. */
async function open<T>(url: string, page: Type<T>): Promise<{ harness: RouterTestingHarness; page: T; el: HTMLElement }> {
  const harness = await RouterTestingHarness.create();
  await harness.navigateByUrl(url);
  const found = harness.fixture.debugElement.query(By.directive(page));
  expect(found).withContext(`${url} did not render ${page.name}`).not.toBeNull();
  return { harness, page: found.componentInstance, el: found.nativeElement };
}

const text = (el: HTMLElement) => el.textContent!.replace(/\s+/g, ' ').trim();
const panelTitles = (el: HTMLElement) =>
  Array.from(el.querySelectorAll('app-report-panel h2')).map(h => (h as HTMLElement).textContent!.trim());

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
    expect(el.querySelectorAll('[data-testid="report-pending"]').length).toBe(3);
    expect(Array.from(el.querySelectorAll('.stat-label')).map(l => l.textContent))
      .toEqual(['Orders submitted', 'Filled', 'Rejected', 'Awaiting execution']);
    expect(text(el.querySelector('[data-testid="filter-summary"]')!)).toBe('Showing all orders.');
    http.expectNone(() => true);
  });

  it('reads the audit filter from the URL', async () => {
    const { page, el } = await open('/reporting?status=REJECTED&symbol=MSFT', TradingOpsDashboardComponent);

    expect(page.filters.value()).toEqual(jasmine.objectContaining({ status: 'REJECTED', symbol: 'MSFT', clientId: null }));
    expect(text(el.querySelector('[data-testid="filter-summary"]')!))
      .toBe('Showing orders matching 2 filters.');
  });

  it('keeps applied filters in the URL', async () => {
    const { harness, el } = await open('/reporting', TradingOpsDashboardComponent);

    type(el, 'input[formControlName="symbol"]', 'aapl');
    type(el, 'input[formControlName="clientId"]', 'c-1');
    el.querySelector('form[aria-label="Audit filters"]')!.dispatchEvent(new Event('submit'));
    await harness.fixture.whenStable();
    harness.detectChanges();

    expect(router.url).toBe('/reporting?clientId=c-1&symbol=AAPL');
    expect(text(el.querySelector('[data-testid="filter-summary"]')!)).toBe('Showing orders matching 2 filters.');
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
    expect(el.querySelectorAll('[data-testid="lifecycle-stage"]').length).toBe(7);
    expect(panelTitles(el)).toEqual(['Order summary', 'Lifecycle timeline', 'Executions', 'Cash entries', 'Position movements']);
    expect(el.querySelector('[role="alert"]')).toBeNull();
    http.expectNone(() => true);
  });

  it('explains when the address does not hold an order id', async () => {
    const { el } = await open('/reporting/orders/not-an-order', OrderLifecycleComponent);

    expect(el.querySelector('[role="alert"]')!.textContent).toContain("isn't an order id");
    expect(el.querySelector('app-lifecycle-timeline')).toBeNull();
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

  it('opens at /reporting with a place for every business report, and loads nothing yet', async () => {
    const { el } = await open('/reporting', AnalystDashboardComponent);

    expect(router.url).toBe('/reporting');
    expect(el.querySelector('h1')!.textContent).toContain('Trading Activity');
    expect(panelTitles(el)).toEqual([
      'Trading volume over time', 'Most traded instruments', 'Average order value over time', 'Client registrations over time'
    ]);
    expect(Array.from(el.querySelectorAll('.stat-label')).map(l => l.textContent))
      .toEqual(['Trading volume', 'Average order value', 'New registrations', 'Active clients']);
    expect(text(el.querySelector('[data-testid="period-summary"]')!)).toMatch(/^Past month: /);
    http.expectNone(() => true);
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

      expect(harness.fixture.debugElement.query(By.directive(page))).withContext(`${to} did not get ${page.name}`).not.toBeNull();
    });
  }
});
