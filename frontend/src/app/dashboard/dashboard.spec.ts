import { signal } from '@angular/core';
import { ComponentFixture, TestBed, fakeAsync, tick } from '@angular/core/testing';
import { ActivatedRoute, Router, convertToParamMap, provideRouter } from '@angular/router';
import { BehaviorSubject, Subject, of, throwError } from 'rxjs';
import { BalanceService } from '../services/balance';
import { HoldingsService } from '../services/holdings';
import { MarketDataService, Quote } from '../services/market-data';
import { OpenOrder, OrderHistoryPage, OrderService, OrderUpdate } from '../services/order';
import { OrderStreamService } from '../services/order-stream';
import { PortfolioHistory, PortfolioService } from '../services/portfolio';
import { PriceStreamService } from '../services/price-stream';
import { MarketStatusService } from '../services/market-status';
import { MarketSession, MarketStatus } from '../shared/market-session';
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
      maxSlippagePercent: 2, createdAt: '', inactiveSince: null, accountSeq: 1 }
  ];
  const day = 86_400_000;
  const history: PortfolioHistory = {
    range: '1D', intervalSeconds: 300, startValue: 2000, endValue: 2500, netContributions: 0, change: 500,
    changePercent: 25,
    points: [{ timestamp: new Date(Date.now() - day).toISOString(), value: 2000, contributions: 0, session: 'REGULAR' },
      { timestamp: new Date().toISOString(), value: 2400, contributions: 0, session: 'REGULAR' }]
  };

  let params: BehaviorSubject<ReturnType<typeof convertToParamMap>>;
  let fixture: ComponentFixture<DashboardComponent>;
  let dashboard: DashboardComponent;
  let holdingsApi: MockedObject<HoldingsService>;
  let balanceApi: MockedObject<BalanceService>;
  let marketApi: MockedObject<MarketDataService>;
  let orderApi: MockedObject<OrderService>;
  let portfolioApi: MockedObject<PortfolioService>;
  let stream: {
    prices: ReturnType<typeof signal<Record<string, number>>>;
    settledPrices: ReturnType<typeof signal<Record<string, number>>>;
    watch: Mock;
  };
  let orderStream: { updates: Subject<OrderUpdate>; start: Mock; stop: Mock };
  let marketSession: ReturnType<typeof signal<MarketSession | null>>;
  let nextChange: ReturnType<typeof signal<string | null>>;
  let marketStatus: ReturnType<typeof signal<MarketStatus | null>>;

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
      ['getAllLatestPrices', 'getPreviousClose', 'getIntradayCloses', 'getQuote']);
    orderApi = createSpyObj('OrderService', ['getOrderHistory', 'getOpenOrders']);
    portfolioApi = createSpyObj('PortfolioService', ['getHistory']);
    stream = {
      prices: signal<Record<string, number>>({ SPX: 5100 }),
      settledPrices: signal<Record<string, number>>({ SPX: 5100 }),
      watch: vi.fn().mockName('watch')
    };
    orderStream = { updates: new Subject<OrderUpdate>(), start: vi.fn().mockName('start'), stop: vi.fn().mockName('stop') };
    marketSession = signal<MarketSession | null>(null);
    nextChange = signal<string | null>(null);
    marketStatus = signal<MarketStatus | null>(null);

    holdingsApi.getHoldings.mockReturnValue(of(holdings));
    holdingsApi.getAccounts.mockReturnValue(of(summaries));
    balanceApi.getBalance.mockReturnValue(of(balance));
    marketApi.getAllLatestPrices.mockReturnValue(of([
      { symbol: 'AAPL', name: 'Apple Inc.', price: 150, asOf: '' },
      { symbol: 'MSFT', name: 'Microsoft', price: 300, asOf: '' }
    ]));
    marketApi.getPreviousClose.mockImplementation(symbol => of(symbol === 'SPX' ? 5000 : 140));
    marketApi.getIntradayCloses.mockReturnValue(of({ closes: [145, 150], starts: [] }));
    orderApi.getOrderHistory.mockReturnValue(of({ content: [{ orderId: 'o1' }] } as OrderHistoryPage));
    orderApi.getOpenOrders.mockReturnValue(of([]));
    marketApi.getQuote.mockReturnValue(of({ symbol: 'AAPL', bidPrice: 149.9, askPrice: 150.1, lastPrice: 150 } as Quote));
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
        { provide: PriceStreamService, useValue: stream },
        { provide: OrderStreamService, useValue: orderStream },
        { provide: MarketStatusService, useValue: { session: marketSession, nextChange, status: marketStatus } }
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

    it('moves totals at the settled price, while each holding follows the live one', () => {
      stream.prices.set({ AAPL: 160 });
      expect(dashboard.holdings()[0].price).toBe(160);
      expect(dashboard.marketValue()).toBe(2250);
      expect(dashboard.portfolioValue()).toBe(3750);

      stream.settledPrices.set({ AAPL: 160 });
      expect(dashboard.marketValue()).toBe(2400);
      expect(dashboard.todaysReturn()).toBe(300);
    });

    it('counts shares bought today from what they cost, and shares sold today up to their sale', () => {
      holdingsApi.getHoldings.mockReturnValue(of({
        accounts: [{
          accountId: 'a1', accountNumber: 'ACC-0001', baseCurrency: 'USD',
          // Up on the day (140 to 150), but bought today at 152.
          positions: [{ ...position('AAPL', 10, 152), quantityAtOpen: 0, todayCashFlow: -1520 }],
          // Sold all 2 MSFT at 300, from a 140 close.
          closedToday: [{ ...position('MSFT', 0, 0), quantityAtOpen: 2, todayCashFlow: 600 }]
        }]
      }));
      dashboard.loadAccounts();
      expect(dashboard.todaysReturn()).toBe(1500 - 1520 + 600 - 280);
      expect(dashboard.todaysReturnPercent()).toBeCloseTo((300 / (1520 + 280)) * 100);
      expect(dashboard.holdings().map(h => h.symbol)).toEqual(['AAPL']);
    });

    it('keeps the last chart point at the live value', () => {
      const points = dashboard.chartPoints();
      expect(points.length).toBe(2);
      expect(points[1].value).toBe(3750);
      expect(dashboard.baseline()).toBe(2000);
      expect(dashboard.headlineValue()).toBe(3750);
      // Today's live change is today's return, so the headline and the stat agree.
      expect(dashboard.headlineChange()).toBe(150);
      expect(dashboard.headlineChangePercent()).toBe(dashboard.todaysReturnPercent());
      expect(dashboard.tone()).toBe('gain');
      expect(dashboard.activeRange()).toBe('1D');
      expect(dashboard.chartError()).toBeNull();
      expect(dashboard.headlineLabel()).toBe('Today');
      expect(portfolioApi.getHistory).toHaveBeenCalledWith('1D', null);
    });

    it('measures longer ranges from the chart, leaving money deposited during them out', () => {
      portfolioApi.getHistory.mockReturnValue(of({
        ...history,
        points: [history.points[0], { ...history.points[1], contributions: 1000 }]
      }));
      dashboard.setRange('1W');
      // 3,750 now, from 2,000 at the start, 1,000 of it deposited since.
      expect(dashboard.headlineChange()).toBe(750);
      expect(dashboard.headlineChangePercent()).toBeCloseTo(25);
      dashboard.scrubIndex.set(0);
      expect(dashboard.headlineChange()).toBe(0);
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

    it('marks today outside regular hours, with the live point in the session now', () => {
      portfolioApi.getHistory.mockReturnValue(of({
        ...history,
        points: [{ ...history.points[0], session: 'PRE_MARKET' }, history.points[1]]
      }));
      dashboard.loadHistory();
      expect(dashboard.chartPoints().map(p => p.session)).toEqual(['PRE_MARKET', 'REGULAR']);
      dashboard.scrubIndex.set(0);
      expect(dashboard.headlineLabel()).toMatch(/:\d{2}\s*[AP]M · Pre-market$/);

      // The close passes: the live point turns after-hours without a reload.
      marketSession.set('AFTER_HOURS');
      expect(dashboard.chartPoints()[1].session).toBe('AFTER_HOURS');
      dashboard.scrubIndex.set(1);
      expect(dashboard.headlineLabel()).toMatch(/:\d{2}\s*[AP]M · After-hours$/);
    });

    it('says under today\'s chart what the market is doing outside regular hours', () => {
      expect(dashboard.sessionCaption()).toBeNull();
      marketSession.set('REGULAR');
      nextChange.set('Closes in 2h 14m');
      expect(dashboard.sessionCaption()).toBeNull();
      marketSession.set('AFTER_HOURS');
      nextChange.set('Ends in 1h 5m');
      expect(dashboard.sessionCaption()).toBe('After-hours · Ends in 1h 5m');
      marketSession.set('CLOSED');
      nextChange.set('Opens Mon 9:30 AM ET');
      expect(dashboard.sessionCaption()).toBe('Market closed · Opens Mon 9:30 AM ET');
      // A pinned demo session has nothing coming next.
      nextChange.set(null);
      expect(dashboard.sessionCaption()).toBe('Market closed');
      portfolioApi.getHistory.mockReturnValue(of({ ...history, range: '1W' }));
      dashboard.setRange('1W');
      expect(dashboard.sessionCaption()).toBeNull();
    });

    describe('after the regular close', () => {
      // Yesterday's close, today's regular close at 3,700, then after-hours; live it's 3,750.
      const afterClose: PortfolioHistory = {
        ...history,
        points: [
          { ...history.points[0], session: 'REGULAR' },
          { ...history.points[0], value: 3700, session: 'REGULAR' },
          { ...history.points[1], value: 3720, session: 'AFTER_HOURS' },
          { ...history.points[1], value: 3740, session: 'AFTER_HOURS' }
        ]
      };

      beforeEach(() => {
        marketSession.set('AFTER_HOURS');
        portfolioApi.getHistory.mockReturnValue(of(afterClose));
        dashboard.loadHistory();
      });

      it('splits the move since the close out of today\'s change', () => {
        expect(dashboard.afterHours()!.change).toBe(50);
        expect(dashboard.afterHours()!.percent).toBeCloseTo(50 / 3700 * 100);
        // Today's return is 150: 100 of it in regular hours, 50 after.
        expect(dashboard.todaysReturn()).toBe(150);
        expect(dashboard.headlineChange()).toBe(100);
        expect(dashboard.headlineChangePercent()).toBeCloseTo(dashboard.todaysReturnPercent()! * 100 / 150);
      });

      it('keeps it through the night, until the next day begins', () => {
        marketSession.set('CLOSED');
        expect(dashboard.afterHours()!.change).toBe(50);
      });

      it('leaves money put in since the close out of it', () => {
        portfolioApi.getHistory.mockReturnValue(of({
          ...afterClose,
          points: [...afterClose.points.slice(0, 3), { ...afterClose.points[3], contributions: 30 }]
        }));
        dashboard.loadHistory();
        expect(dashboard.afterHours()!.change).toBe(20);
      });

      it('shows nothing extra while scrubbing, in regular hours or before a regular session', () => {
        dashboard.scrubIndex.set(1);
        expect(dashboard.afterHours()).toBeNull();
        dashboard.scrubIndex.set(null);
        marketSession.set('REGULAR');
        expect(dashboard.afterHours()).toBeNull();
        expect(dashboard.headlineChange()).toBe(150);

        marketSession.set('AFTER_HOURS');
        portfolioApi.getHistory.mockReturnValue(of({
          ...afterClose,
          points: [afterClose.points[0], ...afterClose.points.slice(2)]
        }));
        dashboard.loadHistory();
        expect(dashboard.afterHours()).toBeNull();
        expect(dashboard.headlineChangePercent()).toBe(dashboard.todaysReturnPercent());
      });
    });

    it('tells which session each of the traded symbol\'s closes traded in, and when the market is closed', () => {
      marketApi.getIntradayCloses.mockReturnValue(of({
        closes: [150, 151], starts: [Date.parse('2026-10-13T14:00:00Z'), Date.parse('2026-10-13T21:00:00Z')]
      }));
      dashboard.selectSymbol('MSFT');
      fixture.detectChanges();
      expect(dashboard.selectedHistory()).toEqual([150, 151]);
      expect(dashboard.selectedSessions()).toBeNull();
      marketStatus.set({
        session: 'CLOSED', now: '2026-10-17T15:00:00Z', nextSession: 'PRE_MARKET', nextTransitionAt: null,
        nextRegularOpen: null, mode: 'REAL', forced: false,
        today: { date: '2026-10-17', open: false, holiday: null, earlyClose: false, preMarketOpen: null,
          regularOpen: null, regularClose: null, afterHoursClose: null }
      });
      expect(dashboard.selectedSessions()).toEqual(['REGULAR', 'AFTER_HOURS']);

      expect(dashboard.marketClosed()).toBe(false);
      marketSession.set('CLOSED');
      expect(dashboard.marketClosed()).toBe(true);
    });

    it('highlights the session under way, or the last one traded in while closed', () => {
      portfolioApi.getHistory.mockReturnValue(of({
        ...history,
        points: [history.points[0], { ...history.points[0], session: 'REGULAR' },
          { ...history.points[1], session: 'AFTER_HOURS' }, { ...history.points[1], session: 'CLOSED' }]
      }));
      dashboard.loadHistory();
      marketSession.set('AFTER_HOURS');
      expect(dashboard.chartHighlight()).toBe('AFTER_HOURS');
      marketSession.set('PRE_MARKET');
      expect(dashboard.chartHighlight()).toBe('PRE_MARKET');
      // Closed: the live point is closed too, so the last session traded in.
      marketSession.set('CLOSED');
      expect(dashboard.chartHighlight()).toBe('AFTER_HOURS');

      portfolioApi.getHistory.mockReturnValue(of({ ...history, range: '1W' }));
      dashboard.setRange('1W');
      expect(dashboard.chartHighlight()).toBeNull();
    });

    it('frames today by where the day is, laying it out by time', () => {
      const hours = {
        preMarketOpen: '2026-10-14T08:00:00Z', regularOpen: '2026-10-14T13:30:00Z',
        regularClose: '2026-10-14T20:00:00Z', afterHoursClose: '2026-10-15T00:00:00Z'
      };
      const at = (iso: string) => Date.parse(iso);
      expect(dashboard.chartDomain()).toBeNull();
      portfolioApi.getHistory.mockReturnValue(of({ ...history, hours }));
      dashboard.loadHistory();

      // Pre-market: the morning at the start, with room for the regular session.
      marketSession.set('PRE_MARKET');
      expect(dashboard.chartDomain()).toEqual({ start: at(hours.preMarketOpen), end: at(hours.regularClose), leadInFrom: null });
      marketSession.set('REGULAR');
      expect(dashboard.chartDomain()).toEqual({ start: at(hours.regularOpen), end: at(hours.regularClose) });
      // After the close, overnight and at the weekend: the open to the end of after-hours.
      marketSession.set('AFTER_HOURS');
      expect(dashboard.chartDomain()).toEqual({ start: at(hours.regularOpen), end: at(hours.afterHoursClose) });
      marketSession.set('CLOSED');
      expect(dashboard.chartDomain()).toEqual({ start: at(hours.regularOpen), end: at(hours.afterHoursClose) });

      // A pre-market pinned for a demo over a day that has already traded past the open: a whole day.
      portfolioApi.getHistory.mockReturnValue(of({ ...history, hours, points: [
        { ...history.points[0], timestamp: '2026-10-13T20:00:00Z' },
        { ...history.points[0], timestamp: '2026-10-14T15:00:00Z', session: 'REGULAR' },
        { ...history.points[1], timestamp: '2026-10-15T04:00:00Z' }
      ] }));
      dashboard.loadHistory();
      marketSession.set('PRE_MARKET');
      expect(dashboard.chartDomain()).toEqual({ start: at(hours.regularOpen), end: at(hours.afterHoursClose) });

      portfolioApi.getHistory.mockReturnValue(of({ ...history, range: '1W', hours: null }));
      dashboard.setRange('1W');
      expect(dashboard.chartDomain()).toBeNull();
    });

    it('draws longer ranges without sessions', () => {
      marketSession.set('AFTER_HOURS');
      portfolioApi.getHistory.mockReturnValue(of({
        ...history, range: '1W',
        points: [{ ...history.points[0], session: 'PRE_MARKET' }, history.points[1]]
      }));
      dashboard.setRange('1W');
      expect(dashboard.chartPoints().map(p => p.session)).toEqual([undefined, undefined]);
      dashboard.scrubIndex.set(0);
      expect(dashboard.headlineLabel()).not.toContain('Pre-market');
    });

    it('notes a scrub before the account was first funded, where nothing changed', () => {
      portfolioApi.getHistory.mockReturnValue(of({
        ...history, range: '1W',
        points: [{ ...history.points[0], beforeFunding: true }, history.points[1]]
      }));
      dashboard.setRange('1W');
      expect(dashboard.chartPoints()[0].beforeFunding).toBe(true);
      expect(dashboard.chartPoints()[1].beforeFunding).toBeUndefined();
      dashboard.scrubIndex.set(0);
      expect(dashboard.headlineLabel()).toMatch(/ · Before first deposit$/);
      expect(dashboard.headlineChange()).toBe(0);
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
      expect(portfolioApi.getHistory).toHaveBeenCalledWith('1M', null);
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
      orderApi.getOpenOrders.mockClear();
      dashboard.onOrderPlaced();
      expect(holdingsApi.getHoldings).toHaveBeenCalled();
      expect(portfolioApi.getHistory).toHaveBeenCalled();
      expect(orderApi.getOrderHistory).toHaveBeenCalled();
      expect(orderApi.getOpenOrders).toHaveBeenCalled();
    });

    describe('open orders', () => {
      const openOrder = (overrides: Partial<OpenOrder>): OpenOrder => ({
        orderId: 'open-1', accountId: 'a1', symbol: 'AAPL', side: 'BUY', quantity: 2, status: 'OPEN',
        orderType: 'LIMIT', limitPrice: 140, stopPrice: null, timeInForce: 'DAY', submittedAt: '',
        expiresAt: null, triggeredAt: null, reservedCash: 280, ...overrides
      });

      it('takes what open orders hold out of buying power and free shares', () => {
        orderApi.getOpenOrders.mockReturnValue(of([
          openOrder({}),
          openOrder({ orderId: 'open-2', side: 'SELL', quantity: 4, orderType: 'STOP', limitPrice: null,
            stopPrice: 120, reservedCash: null })
        ]));
        dashboard.loadOpenOrders();

        const [first] = dashboard.tradeAccounts();
        expect(first.buyingPower).toBe(720);
        expect(first.cashOnHold).toBe(280);
        expect(first.sharesOnHold).toEqual({ AAPL: 4 });
        expect(first.averageCost).toEqual({ AAPL: 100 });
        // Cash still counts in the portfolio's value; buying power doesn't include what's held.
        expect(dashboard.cash()).toBe(1500);
        expect(dashboard.buyingPower()).toBe(1220);
      });

      it('reloads and announces an open order that filled', () => {
        orderApi.getOpenOrders.mockReturnValue(of([openOrder({})]));
        dashboard.loadOpenOrders();
        holdingsApi.getHoldings.mockClear();
        orderApi.getOrderHistory.mockReturnValue(of({ content: [{
          orderId: 'open-1', symbol: 'AAPL', side: 'BUY', quantity: 2, status: 'FILLED', submittedAt: '',
          filledAt: '', executions: [{ executionId: 'e1', quantity: 2, price: 139.5, executedAt: '' }]
        }] } as OrderHistoryPage));

        orderApi.getOpenOrders.mockReturnValue(of([]));
        dashboard.loadOpenOrders();

        expect(dashboard.openOrders()).toEqual([]);
        expect(holdingsApi.getHoldings).toHaveBeenCalled();
        expect(dashboard.notice()).toEqual({ text: 'Limit buy of 2 AAPL filled at $139.50', tone: 'gain' });
        dashboard.dismissNotice();
        expect(dashboard.notice()).toBeNull();
      });

      it('drops a cancelled order at once, without announcing it', () => {
        orderApi.getOpenOrders.mockReturnValue(of([openOrder({})]));
        dashboard.loadOpenOrders();
        orderApi.getOpenOrders.mockReturnValue(of([]));

        dashboard.onOpenOrderCancelled(openOrder({}));

        expect(dashboard.openOrders()).toEqual([]);
        expect(dashboard.notice()).toBeNull();
      });

      it("reads open orders' quotes, for how far ask and bid sit from the last trade", () => {
        orderApi.getOpenOrders.mockReturnValue(of([openOrder({})]));
        dashboard.loadOpenOrders();
        expect(marketApi.getQuote).toHaveBeenCalledWith('AAPL');
        expect(dashboard.quoteOffsets()['AAPL'].ask).toBeCloseTo(0.1);
        expect(dashboard.quoteOffsets()['AAPL'].bid).toBeCloseTo(-0.1);
      });

      it("loads the traded symbol's recent closes for the order chart", () => {
        expect(marketApi.getIntradayCloses).toHaveBeenCalledWith('AAPL');
        expect(dashboard.selectedHistory()).toEqual([145, 150]);
      });

      describe('order stream', () => {
        const update = (overrides: Partial<OrderUpdate>): OrderUpdate => ({
          orderId: 'open-1', symbol: 'AAPL', side: 'BUY', quantity: 2, orderType: 'LIMIT', status: 'FILLED',
          fillPrice: 139.5, reason: null, at: '', ...overrides
        });

        it('listens while the dashboard is open, and stops when it closes', () => {
          expect(orderStream.start).toHaveBeenCalled();
          fixture.destroy();
          expect(orderStream.stop).toHaveBeenCalled();
        });

        it('announces a fill at once and brings the page up to date', () => {
          holdingsApi.getHoldings.mockClear();
          orderApi.getOpenOrders.mockClear();
          orderStream.updates.next(update({}));

          expect(dashboard.notice()).toEqual({ text: 'Limit buy of 2 AAPL filled at $139.50', tone: 'gain' });
          expect(holdingsApi.getHoldings).toHaveBeenCalled();
          expect(orderApi.getOpenOrders).toHaveBeenCalled();
        });

        it('says why an order was refused, and when one expired', () => {
          orderStream.updates.next(update({ status: 'REJECTED', fillPrice: null, reason: 'Insufficient funds - order rejected' }));
          expect(dashboard.notice()).toEqual({
            text: 'Limit buy of 2 AAPL couldn\u2019t be filled \u2014 Insufficient funds - order rejected', tone: 'loss'
          });
          orderStream.updates.next(update({ status: 'EXPIRED', fillPrice: null, orderType: 'TRAILING_STOP', side: 'SELL' }));
          expect(dashboard.notice()).toEqual({ text: 'Trailing stop sell of 2 AAPL expired', tone: 'flat' });
        });

        it('does not announce the same fill again when the next poll finds the order gone', () => {
          orderApi.getOpenOrders.mockReturnValue(of([openOrder({})]));
          dashboard.loadOpenOrders();
          orderStream.updates.next(update({}));
          dashboard.dismissNotice();

          orderApi.getOpenOrders.mockReturnValue(of([]));
          dashboard.loadOpenOrders();

          expect(dashboard.notice()).toBeNull();
        });
      });

      it('carries on without open orders when they cannot be loaded', () => {
        orderApi.getOpenOrders.mockReturnValue(throwError(() => ({ status: 503 })));
        dashboard.loadOpenOrders();
        expect(dashboard.openOrders()).toEqual([]);
      });
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
      expect(dashboard.activeRange()).toBe('1D');
      expect(dashboard.chartError()).toBeNull();
    });

    it('charts the account from its own history, ending at the live value', () => {
      expect(portfolioApi.getHistory).toHaveBeenCalledWith('1D', 'a2');
      const points = dashboard.chartPoints();
      expect(points.length).toBe(2);
      expect(points[1].value).toBe(1250);
      expect(dashboard.baseline()).toBe(2000);
      expect(dashboard.chartLoading()).toBe(false);
    });

    it('labels a scrub with the time of day', () => {
      dashboard.scrubIndex.set(0);
      expect(dashboard.headlineLabel()).toMatch(/\d{1,2}:\d{2}/);
    });

    it('charts the account over other ranges too', () => {
      portfolioApi.getHistory.mockClear();
      dashboard.setRange('1M');
      expect(dashboard.range()).toBe('1M');
      expect(portfolioApi.getHistory).toHaveBeenCalledWith('1M', 'a2');
    });

    it('selects a symbol held in the account when the scope changes', () => {
      expect(dashboard.selectedSymbol()).toBe('AAPL');
      portfolioApi.getHistory.mockClear();
      params.next(convertToParamMap({ accountId: 'a1' }));
      fixture.detectChanges();
      expect(dashboard.scrubIndex()).toBeNull();
      expect(dashboard.selectedAccount()?.accountId).toBe('a1');
      expect(portfolioApi.getHistory).toHaveBeenCalledWith('1D', 'a1');
    });

    it('drops a chart that arrives for the scope it left', () => {
      const slow = new Subject<PortfolioHistory>();
      portfolioApi.getHistory.mockReturnValue(slow);
      params.next(convertToParamMap({ accountId: 'a1' }));
      fixture.detectChanges();
      portfolioApi.getHistory.mockReturnValue(of(history));
      params.next(convertToParamMap({ accountId: 'a2' }));
      fixture.detectChanges();
      slow.next({ ...history, startValue: 1 });
      expect(dashboard.baseline()).toBe(2000);
    });
  });

  describe('an account with nothing invested', () => {
    it('still charts its cash', () => {
      holdingsApi.getHoldings.mockReturnValue(of({
        accounts: [{ accountId: 'a2', accountNumber: 'ACC-0002', baseCurrency: 'USD', positions: [] }]
      }));
      create('a2');
      expect(dashboard.chartPoints().length).toBe(2);
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
