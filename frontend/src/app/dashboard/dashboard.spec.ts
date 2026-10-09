import { signal } from '@angular/core';
import { ComponentFixture, TestBed, fakeAsync, tick } from '@angular/core/testing';
import { ActivatedRoute, Router, convertToParamMap, provideRouter } from '@angular/router';
import { BehaviorSubject, Subject, of, throwError } from 'rxjs';
import { BalanceService } from '../services/balance';
import { HoldingsService } from '../services/holdings';
import { MarketDataService } from '../services/market-data';
import { OrderHistoryPage, OrderService } from '../services/order';
import { PortfolioHistory, PortfolioService } from '../services/portfolio';
import { PriceStreamService } from '../services/price-stream';
import { DashboardComponent } from './dashboard';
import type { Mock, MockedObject } from 'vitest';
import { createSpyObj } from '../../testing/create-spy-obj';

describe('DashboardComponent', () => {
  const position = (symbol: string, quantity: number, averageCost: number) =>
    ({ instrumentId: symbol, symbol, instrumentName: symbol + ' Inc.', assetClass: 'EQUITY', quantity, averageCost });
  const holdings = {
    accounts: [
      { accountId: 'a1', accountNumber: 'ACC-0001', baseCurrency: 'USD', positions: [position('AAPL', 10, 100)] },
      { accountId: 'a2', accountNumber: 'ACC-0002', baseCurrency: 'USD', positions: [position('AAPL', 5, 140)] }
    ]
  };
  const balance = {
    accounts: [
      { accountId: 'a1', accountNumber: 'ACC-0001', currency: 'USD', balance: 1000 },
      { accountId: 'a2', accountNumber: 'ACC-0002', currency: 'USD', balance: 500 }
    ],
    totalsByCurrency: {}
  };
  const summaries = [
    { accountId: 'a1', accountNumber: 'ACC-0001', status: 'ACTIVE', baseCurrency: 'USD', tradingEnabled: true,
      maxSlippagePercent: 2, createdAt: '', inactiveSince: null }
  ];
  const day = 86_400_000;
  const history: PortfolioHistory = {
    range: '1D', intervalSeconds: 300, startValue: 2000, endValue: 2500, change: 500, changePercent: 25,
    points: [{ timestamp: new Date(Date.now() - day).toISOString(), value: 2000 },
      { timestamp: new Date().toISOString(), value: 2400 }]
  };

  let params: BehaviorSubject<ReturnType<typeof convertToParamMap>>;
  let fixture: ComponentFixture<DashboardComponent>;
  let dashboard: DashboardComponent;
  let holdingsApi: MockedObject<HoldingsService>;
  let balanceApi: MockedObject<BalanceService>;
  let marketApi: MockedObject<MarketDataService>;
  let orderApi: MockedObject<OrderService>;
  let portfolioApi: MockedObject<PortfolioService>;
  let stream: { prices: ReturnType<typeof signal<Record<string, number>>>; watch: Mock };

  function create(accountId: string | null = null): void {
    params.next(convertToParamMap(accountId ? { accountId } : {}));
    fixture = TestBed.createComponent(DashboardComponent);
    dashboard = fixture.componentInstance;
    fixture.detectChanges();
  }

  beforeEach(() => {
    params = new BehaviorSubject(convertToParamMap({}));
    holdingsApi = createSpyObj('HoldingsService', ['getHoldings', 'getAccounts']);
    balanceApi = createSpyObj('BalanceService', ['getBalance']);
    marketApi = createSpyObj('MarketDataService',
      ['getAllLatestPrices', 'getPreviousClose', 'getIntradayCloses']);
    orderApi = createSpyObj('OrderService', ['getOrderHistory']);
    portfolioApi = createSpyObj('PortfolioService', ['getHistory']);
    stream = { prices: signal<Record<string, number>>({ SPX: 5100 }), watch: vi.fn().mockName('watch') };

    holdingsApi.getHoldings.mockReturnValue(of(holdings));
    holdingsApi.getAccounts.mockReturnValue(of(summaries));
    balanceApi.getBalance.mockReturnValue(of(balance));
    marketApi.getAllLatestPrices.mockReturnValue(of([
      { symbol: 'AAPL', name: 'Apple Inc.', price: 150, asOf: '' },
      { symbol: 'MSFT', name: 'Microsoft', price: 300, asOf: '' }
    ]));
    marketApi.getPreviousClose.mockImplementation(symbol => of(symbol === 'SPX' ? 5000 : 140));
    marketApi.getIntradayCloses.mockReturnValue(of([145, 150]));
    orderApi.getOrderHistory.mockReturnValue(of({ content: [{ orderId: 'o1' }] } as OrderHistoryPage));
    portfolioApi.getHistory.mockReturnValue(of(history));

    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        { provide: ActivatedRoute, useValue: { paramMap: params.asObservable() } },
        { provide: HoldingsService, useValue: holdingsApi },
        { provide: BalanceService, useValue: balanceApi },
        { provide: MarketDataService, useValue: marketApi },
        { provide: OrderService, useValue: orderApi },
        { provide: PortfolioService, useValue: portfolioApi },
        { provide: PriceStreamService, useValue: stream }
      ]
    });
    // The children are covered by their own specs; this one drives the page's own logic.
    TestBed.overrideComponent(DashboardComponent, { set: { imports: [], template: '' } });
  });

  describe('all accounts', () => {
    beforeEach(() => create());

    it('loads accounts, history and orders, and picks the first held symbol', () => {
      expect(dashboard.accountsLoading()).toBe(false);
      expect(dashboard.orders().length).toBe(1);
      expect(dashboard.ordersLoading()).toBe(false);
      expect(dashboard.historyLoading()).toBe(false);
      expect(dashboard.selectedSymbol()).toBe('AAPL');
      expect(dashboard.selectedName()).toBe('AAPL Inc.');
      expect(dashboard.selectedPrice()).toBe(150);
      expect(dashboard.isAccountView()).toBe(false);
    });

    it('values the whole portfolio', () => {
      expect(dashboard.cash()).toBe(1500);
      expect(dashboard.marketValue()).toBe(2250);
      expect(dashboard.portfolioValue()).toBe(3750);
      expect(dashboard.todaysReturn()).toBe(150);
      expect(dashboard.todaysReturnPercent()).toBeCloseTo((150 / 2100) * 100);
      expect(dashboard.totalReturn()).toBe(2250 - 1700);
    });

    it('keeps the last chart point at the live value', () => {
      const points = dashboard.chartPoints();
      expect(points.length).toBe(2);
      expect(points[1].value).toBe(3750);
      expect(dashboard.baseline()).toBe(2000);
      expect(dashboard.headlineValue()).toBe(3750);
      expect(dashboard.headlineChange()).toBe(1750);
      expect(dashboard.tone()).toBe('gain');
      expect(dashboard.visibleRanges().length).toBe(6);
      expect(dashboard.activeRange()).toBe('1D');
      expect(dashboard.chartEmpty()).toBe(false);
      expect(dashboard.chartError()).toBeNull();
      expect(dashboard.headlineLabel()).toBe('Today');
    });

    it('shows the scrubbed point in the headline', () => {
      dashboard.scrubIndex.set(0);
      expect(dashboard.headlineValue()).toBe(2000);
      expect(dashboard.headlineChange()).toBe(0);
      expect(dashboard.headlineChangePercent()).toBe(0);
      expect(dashboard.headlineLabel()).toMatch(/\d/);
      dashboard.scrubIndex.set(99);
      expect(dashboard.headlineValue()).toBe(3750);
    });

    it('labels a daily-resolution scrub with the year', () => {
      portfolioApi.getHistory.mockReturnValue(of({ ...history, intervalSeconds: 86400 }));
      dashboard.loadHistory();
      dashboard.scrubIndex.set(0);
      expect(dashboard.headlineLabel()).toMatch(/\d{4}/);
    });

    it('names the page by how many accounts there are', () => {
      expect(dashboard.multiAccount()).toBe(true);
      expect(dashboard.heroTitle()).toBe('Total value');
      expect(dashboard.accountLabel()).toBe('2 accounts');
    });

    it('describes the indices, trade accounts and names', () => {
      expect(dashboard.indices()[0]).toEqual({ symbol: 'SPX', label: 'S&P 500', price: 5100, previousClose: 5000 });
      expect(dashboard.indices()[1].price).toBeNull();
      const [first] = dashboard.tradeAccounts();
      expect(first.buyingPower).toBe(1000);
      expect(first.shares).toEqual({ AAPL: 10 });
      expect(first.maxSlippagePercent).toBe(2);
      expect(dashboard.tradeAccounts()[1].maxSlippagePercent).toBeNull();
      expect(dashboard.names()['MSFT']).toBe('Microsoft');
    });

    it('keeps the stream watching everything on screen', () => {
      const symbols = stream.watch.mock.lastCall![0] as string[];
      expect(symbols).toContain('SPX');
      expect(symbols).toContain('AAPL');
    });

    it('changes range only when it differs, reloading history', () => {
      portfolioApi.getHistory.mockClear();
      dashboard.setRange('1D');
      expect(portfolioApi.getHistory).not.toHaveBeenCalled();
      dashboard.setRange('1M');
      expect(dashboard.range()).toBe('1M');
      expect(portfolioApi.getHistory).toHaveBeenCalledWith('1M');
      expect(dashboard.headlineLabel()).toBe('Past month');
    });

    it('explains history failures', () => {
      portfolioApi.getHistory.mockReturnValue(throwError(() => ({ status: 503 })));
      dashboard.loadHistory();
      expect(dashboard.historyError()).toBe('Market data is unavailable right now.');
      expect(dashboard.historyLoading()).toBe(false);
      expect(dashboard.chartError()).toBe('Market data is unavailable right now.');

      portfolioApi.getHistory.mockReturnValue(throwError(() => ({ status: 500 })));
      dashboard.loadHistory();
      expect(dashboard.historyError()).toBe('We couldn’t load your portfolio history.');
    });

    it('discards history that arrives for a range that is no longer selected', () => {
      const slow = new Subject<PortfolioHistory>();
      portfolioApi.getHistory.mockReturnValue(slow);
      dashboard.loadHistory();
      portfolioApi.getHistory.mockReturnValue(of(history));
      dashboard.setRange('1M');
      slow.next({ ...history, startValue: 1 });
      expect(dashboard.history()!.startValue).toBe(2000);

      const failing = new Subject<PortfolioHistory>();
      portfolioApi.getHistory.mockReturnValue(failing);
      dashboard.loadHistory();
      portfolioApi.getHistory.mockReturnValue(of(history));
      dashboard.setRange('1W');
      failing.error({ status: 500 });
      expect(dashboard.historyError()).toBeNull();
    });

    it('explains order failures', () => {
      orderApi.getOrderHistory.mockReturnValue(throwError(() => ({ status: 500 })));
      dashboard.loadOrders();
      expect(dashboard.ordersError()).toBe('We couldn’t load your recent orders.');
      expect(dashboard.ordersLoading()).toBe(false);
    });

    it('reloads everything after an order is placed', () => {
      holdingsApi.getHoldings.mockClear();
      portfolioApi.getHistory.mockClear();
      orderApi.getOrderHistory.mockClear();
      dashboard.onOrderPlaced();
      expect(holdingsApi.getHoldings).toHaveBeenCalled();
      expect(portfolioApi.getHistory).toHaveBeenCalled();
      expect(orderApi.getOrderHistory).toHaveBeenCalled();
    });

    it('selects a symbol and loads its daily context', () => {
      marketApi.getPreviousClose.mockClear();
      dashboard.selectSymbol('MSFT');
      expect(dashboard.selectedSymbol()).toBe('MSFT');
      expect(marketApi.getPreviousClose).toHaveBeenCalledWith('MSFT');
    });

    it('opens the trade panel on a side after selecting a symbol', fakeAsync(() => {
      const open = vi.fn().mockName('open');
      dashboard.tradePanel = { open } as never;
      dashboard.trade('MSFT', 'SELL');
      expect(dashboard.selectedSymbol()).toBe('MSFT');
      tick();
      expect(open).toHaveBeenCalledWith('SELL');
      dashboard.trade('AAPL');
      tick();
      expect(open).toHaveBeenCalledWith('BUY');
    }));

    it('survives a trade request without a trade panel', fakeAsync(() => {
      dashboard.tradePanel = undefined;
      dashboard.trade('MSFT');
      tick();
      expect(dashboard.selectedSymbol()).toBe('MSFT');
    }));
  });

  describe('one account', () => {
    beforeEach(() => create('a2'));

    it('scopes holdings, cash and labels to the account', () => {
      expect(dashboard.isAccountView()).toBe(true);
      expect(dashboard.selectedAccount()?.accountId).toBe('a2');
      expect(dashboard.cash()).toBe(500);
      expect(dashboard.marketValue()).toBe(750);
      expect(dashboard.portfolioValue()).toBe(1250);
      expect(dashboard.heroTitle()).toBe('Brokerage ··0002');
      expect(dashboard.accountLabel()).toBe('ACC-0002');
      expect(dashboard.visibleRanges()).toEqual([]);
      expect(dashboard.activeRange()).toBe('1D');
      expect(dashboard.chartError()).toBeNull();
    });

    it('charts the account from its intraday values, ending at the live value', () => {
      const points = dashboard.chartPoints();
      expect(points.length).toBe(2);
      expect(points[1].value).toBe(1250);
      expect(points[1].time - points[0].time).toBeGreaterThan(0);
      expect(dashboard.baseline()).toBe(selectedPreviousCloseValue());
      expect(dashboard.chartLoading()).toBe(false);
    });

    it('labels a scrub with the time of day', () => {
      dashboard.scrubIndex.set(0);
      expect(dashboard.headlineLabel()).toMatch(/\d{1,2}:\d{2}/);
    });

    it('ignores range changes', () => {
      portfolioApi.getHistory.mockClear();
      dashboard.setRange('1M');
      expect(dashboard.range()).toBe('1D');
      expect(portfolioApi.getHistory).not.toHaveBeenCalled();
    });

    it('selects a symbol held in the account when the scope changes', () => {
      expect(dashboard.selectedSymbol()).toBe('AAPL');
      params.next(convertToParamMap({ accountId: 'a1' }));
      fixture.detectChanges();
      expect(dashboard.scrubIndex()).toBeNull();
      expect(dashboard.selectedAccount()?.accountId).toBe('a1');
    });

    function selectedPreviousCloseValue(): number | null {
      return dashboard.selectedAccount()?.previousCloseValue ?? null;
    }
  });

  describe('an account with nothing invested', () => {
    it('has no chart to draw', () => {
      holdingsApi.getHoldings.mockReturnValue(of({
        accounts: [{ accountId: 'a2', accountNumber: 'ACC-0002', baseCurrency: 'USD', positions: [] }]
      }));
      create('a2');
      expect(dashboard.chartEmpty()).toBe(true);
      expect(dashboard.holdings()).toEqual([]);
      expect(dashboard.selectedSymbol()).toBeNull();
      expect(dashboard.selectedName()).toBeNull();
      expect(dashboard.selectedPrice()).toBeNull();
    });
  });

  describe('before anything has loaded', () => {
    beforeEach(() => {
      holdingsApi.getHoldings.mockReturnValue(new Subject());
      balanceApi.getBalance.mockReturnValue(new Subject());
      portfolioApi.getHistory.mockReturnValue(new Subject());
      create();
    });

    it('reports nothing rather than zeros', () => {
      expect(dashboard.accountsLoading()).toBe(true);
      expect(dashboard.holdings()).toEqual([]);
      expect(dashboard.cash()).toBeNull();
      expect(dashboard.portfolioValue()).toBeNull();
      expect(dashboard.todaysReturn()).toBeNull();
      expect(dashboard.todaysReturnPercent()).toBeNull();
      expect(dashboard.totalReturn()).toBeNull();
      expect(dashboard.chartPoints()).toEqual([]);
      expect(dashboard.headlineChange()).toBeNull();
      expect(dashboard.tone()).toBe('flat');
      expect(dashboard.tradeAccounts()).toEqual([]);
      expect(dashboard.accountLabel()).toBe('');
      expect(dashboard.heroTitle()).toBe('Investing');
      expect(dashboard.historyLoading()).toBe(true);
    });
  });

  describe('a single account', () => {
    it('is labelled by its account number', () => {
      balanceApi.getBalance.mockReturnValue(of({ ...balance, accounts: [balance.accounts[0]] }));
      create();
      expect(dashboard.multiAccount()).toBe(false);
      expect(dashboard.accountLabel()).toBe('ACC-0001');
      expect(dashboard.heroTitle()).toBe('Investing');
    });
  });

  it('falls back to the overview for an account that is not the client\'s', () => {
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
    create('someone-elses');
    expect(navigate).toHaveBeenCalledWith(['/dashboard'], { replaceUrl: true });
  });
});
