import { Type } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { OrderHistoryItem } from '../services/order';
import { AccountDetailsComponent } from './account-details/account-details';
import { AccountSwitcherComponent } from './account-switcher/account-switcher';
import { AccountsOverviewComponent } from './accounts-overview/accounts-overview';
import { AccountView, HoldingView, MarketIndex } from './models';
import { PositionsListComponent } from './positions-list/positions-list';
import { RecentActivityComponent } from './recent-activity/recent-activity';
import { SparklineComponent } from './sparkline/sparkline';
import { TickerStripComponent } from './ticker-strip/ticker-strip';

function account(overrides: Partial<AccountView> = {}): AccountView {
  return {
    accountId: 'a1', accountNumber: 'ACC-0001', name: 'Brokerage ··0001', mask: '0001', color: '#5b9dff',
    status: 'ACTIVE', tradingEnabled: true, openedAt: '2024-01-15T00:00:00Z', inactiveSince: null,
    cash: 100, marketValue: 900, value: 1000, dayChange: 10, dayChangePercent: 1, totalReturn: 50,
    positionCount: 2, share: 0.5, intraday: [1, 2], previousCloseValue: 990, ...overrides
  };
}

function holding(overrides: Partial<HoldingView> = {}): HoldingView {
  return {
    symbol: 'AAPL', name: 'Apple', quantity: 1, averageCost: 1, price: 2, previousClose: 1, marketValue: 2,
    dayChange: 1, dayChangePercent: 100, totalReturn: 1, totalReturnPercent: 100, intraday: [1, 2], ...overrides
  };
}

function render<T>(component: Type<T>, inputs: Record<string, unknown> = {}): ComponentFixture<T> {
  const fixture = TestBed.createComponent(component);
  for (const [key, value] of Object.entries(inputs)) {
    fixture.componentRef.setInput(key, value);
  }
  fixture.detectChanges();
  return fixture;
}

describe('dashboard display components', () => {
  beforeEach(() => TestBed.configureTestingModule({ providers: [provideRouter([])] }));

  describe('SparklineComponent', () => {
    it('draws nothing for fewer than two values', () => {
      const fixture = render(SparklineComponent, { values: [1] });
      expect(fixture.componentInstance.path).toBe('');
      expect(fixture.nativeElement.querySelector('svg')).toBeNull();
    });

    it('colours the line by direction against the baseline', () => {
      const tone = (values: number[], baseline: number | null) =>
        render(SparklineComponent, { values, baseline }).componentInstance.tone;
      expect(tone([1, 3], null)).toBe('gain');
      expect(tone([3, 1], null)).toBe('loss');
      expect(tone([2, 2], null)).toBe('flat');
      expect(tone([2, 3], 5)).toBe('loss');
    });

    it('draws a path and a baseline', () => {
      const fixture = render(SparklineComponent, { values: [1, 2, 3], baseline: 2 });
      expect(fixture.componentInstance.path.startsWith('M0.0')).toBeTrue();
      expect(fixture.nativeElement.querySelector('line.base')).not.toBeNull();
    });
  });

  describe('TickerStripComponent', () => {
    const index = (price: number | null, previousClose: number | null): MarketIndex =>
      ({ symbol: 'SPX', label: 'S&P 500', price, previousClose });

    it('shows direction arrows and signed change', () => {
      const strip = render(TickerStripComponent).componentInstance;
      expect(strip.arrow(index(110, 100))).toBe('▲');
      expect(strip.arrow(index(90, 100))).toBe('▼');
      expect(strip.arrow(index(100, 100))).toBe('');
      expect(strip.arrow(index(100, null))).toBe('');
      expect(strip.dir(index(110, 100))).toBe('gain');
      expect(strip.dir(index(null, 100))).toBe('flat');
      expect(strip.percent(index(110, 100))).toBe('+10.00%');
      expect(strip.trackBySymbol(0, index(1, 1))).toBe('SPX');
    });

    it('renders each index, with a placeholder until priced', () => {
      const fixture = render(TickerStripComponent, { indices: [index(110, 100), index(null, null)] });
      expect(fixture.nativeElement.querySelectorAll('.item').length).toBe(2);
      expect(fixture.nativeElement.querySelectorAll('.placeholder').length).toBe(1);
    });
  });

  describe('RecentActivityComponent', () => {
    const order = (overrides: Partial<OrderHistoryItem>) => ({
      orderId: 'o', symbol: 'AAPL', side: 'BUY', quantity: 2, status: 'FILLED', submittedAt: '2024-01-01T00:00:00Z',
      executions: [], ...overrides
    }) as OrderHistoryItem;

    it('describes how an order filled', () => {
      const activity = render(RecentActivityComponent).componentInstance;
      expect(activity.fillText(order({ status: 'REJECTED' }))).toBe('Not filled');
      expect(activity.fillText(order({}))).toBe('Market order');
      expect(activity.fillText(order({ executions: undefined }))).toBe('Market order');
      expect(activity.fillText(order({
        executions: [{ quantity: 1, price: 10 }, { quantity: 3, price: 20 }] as OrderHistoryItem['executions']
      }))).toBe('Market @ $17.50');
      expect(activity.trackById(0, order({ orderId: 'x' }))).toBe('x');
    });

    it('renders the loading, error, empty and populated states', () => {
      expect(render(RecentActivityComponent, { loading: true }).nativeElement.querySelector('[aria-busy]')).not.toBeNull();
      expect(render(RecentActivityComponent, { error: 'Nope' }).nativeElement.textContent).toContain('Nope');
      expect(render(RecentActivityComponent).nativeElement.textContent).toContain('No orders yet');
      const fixture = render(RecentActivityComponent, { orders: [order({}), order({ side: 'SELL' })] });
      expect(fixture.nativeElement.querySelectorAll('li').length).toBe(2);
    });
  });

  describe('AccountSwitcherComponent', () => {
    it('lists every account and marks the selected one', () => {
      const fixture = render(AccountSwitcherComponent, {
        accounts: [account(), account({ accountId: 'a2', name: 'Two', inactiveSince: '2024-02-01' })], selected: 'a2'
      });
      const tabs = fixture.nativeElement.querySelectorAll('a.tab');
      expect(tabs.length).toBe(3);
      expect(tabs[2].getAttribute('aria-current')).toBe('page');
      expect(tabs[2].textContent).toContain('Inactive');
      expect(fixture.componentInstance.trackById(0, account())).toBe('a1');
    });

    it('shows skeletons while loading', () => {
      const fixture = render(AccountSwitcherComponent, { loading: true });
      expect(fixture.nativeElement.querySelectorAll('.tab-skeleton').length).toBe(2);
    });
  });

  describe('AccountDetailsComponent', () => {
    it('shows skeleton rows with no account', () => {
      expect(render(AccountDetailsComponent).nativeElement.querySelector('[aria-busy]')).not.toBeNull();
    });

    it('shows the facts of an account', () => {
      const text = render(AccountDetailsComponent, { account: account() }).nativeElement.textContent;
      expect(text).toContain('ACC-0001');
      expect(text).toContain('Jan 15, 2024');
      expect(text).toContain('Enabled');
      expect(text).toContain('Manage account');
    });

    it('covers unknown, restricted and inactive accounts without a manage link', () => {
      const fixture = render(AccountDetailsComponent, {
        account: account({ status: null, tradingEnabled: false, openedAt: null, inactiveSince: '2024-02-01' }),
        showManage: false
      });
      const text = fixture.nativeElement.textContent;
      expect(text).toContain('Unknown');
      expect(text).toContain('Restricted');
      expect(text).toContain('Inactive since');
      expect(text).not.toContain('Manage account');
      fixture.componentRef.setInput('account', account({ tradingEnabled: null }));
      fixture.detectChanges();
      expect(fixture.nativeElement.textContent).toContain('—');
    });
  });

  describe('AccountsOverviewComponent', () => {
    it('describes the allocation and rounds shares', () => {
      const overview = render(AccountsOverviewComponent, {
        accounts: [account({ share: 0.256 }), account({ accountId: 'a2', name: 'Two', share: 0.744 })]
      }).componentInstance;
      expect(overview.percent(0.256)).toBe('26%');
      expect(overview.allocationLabel()).toBe('Allocation across accounts: Brokerage ··0001 26%, Two 74%');
      expect(overview.trackById(0, account())).toBe('a1');
    });

    it('renders loading, empty and populated states', () => {
      expect(render(AccountsOverviewComponent, { loading: true }).nativeElement.querySelector('[aria-busy]')).not.toBeNull();
      expect(render(AccountsOverviewComponent).nativeElement.textContent).toContain('any open accounts');
      const fixture = render(AccountsOverviewComponent, {
        accounts: [account({ inactiveSince: '2024-02-01', positionCount: 1 })]
      });
      expect(fixture.nativeElement.querySelectorAll('[data-testid="account-row"]').length).toBe(1);
      expect(fixture.nativeElement.textContent).toContain('1 account');
      expect(fixture.nativeElement.textContent).toContain('Inactive');
    });
  });

  describe('PositionsListComponent', () => {
    it('renders holdings and forwards trades', () => {
      const fixture = render(PositionsListComponent, {
        holdings: [holding(), holding({ symbol: 'MSFT', price: null, marketValue: null })], selected: 'AAPL'
      });
      expect(fixture.componentInstance.trackBySymbol(0, holding())).toBe('AAPL');
      expect(fixture.nativeElement.textContent).toContain('AAPL');
      expect(fixture.nativeElement.textContent).toContain('MSFT');
    });

    it('renders its loading state', () => {
      const fixture = render(PositionsListComponent, { loading: true });
      expect(fixture.nativeElement.textContent).toBeDefined();
    });
  });
});
