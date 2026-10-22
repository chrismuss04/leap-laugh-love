import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Subject, of, throwError } from 'rxjs';
import { AccountValuationService } from './account-valuation';
import { BalanceService } from './balance';
import { HoldingsService } from './holdings';
import { MarketDataService } from './market-data';
import { PortfolioHistory, PortfolioService } from './portfolio';
import { PriceStreamService } from './price-stream';
import type { MockedObject } from 'vitest';
import { createSpyObj } from '../../testing/create-spy-obj';

describe('AccountValuationService', () => {
  const holdings = {
    accounts: [
      { accountId: 'a1', accountNumber: 'ACC-0001', baseCurrency: 'USD', positions: [
        { instrumentId: 'i1', symbol: 'AAPL', instrumentName: 'Apple', assetClass: 'EQUITY', quantity: 10, averageCost: 100 },
        { instrumentId: 'i2', symbol: 'GONE', instrumentName: 'Sold', assetClass: 'EQUITY', quantity: 0, averageCost: 5 }
      ] },
      { accountId: 'a2', accountNumber: 'ACC-0002', baseCurrency: 'USD', positions: [
        { instrumentId: 'i1', symbol: 'AAPL', instrumentName: 'Apple', assetClass: 'EQUITY', quantity: 5, averageCost: 140 }
      ] }
    ]
  };
  const balance = {
    accounts: [
      { accountId: 'a1', accountNumber: 'ACC-0001', currency: 'USD', balance: 1000 },
      { accountId: 'a2', accountNumber: 'ACC-0002', currency: 'USD', balance: 500 }
    ],
    totalsByCurrency: { USD: 1500 }
  };
  const summaries = [
    { accountId: 'a1', accountNumber: 'ACC-0001', status: 'ACTIVE', baseCurrency: 'USD', tradingEnabled: true,
      maxSlippagePercent: null, createdAt: '2024-01-01T00:00:00Z', inactiveSince: null, accountSeq: 1 }
  ];

  const dayHistory: PortfolioHistory = {
    range: '1D', intervalSeconds: 300, startValue: 2400, endValue: 2500, netContributions: 0, change: 100,
    changePercent: 4.17,
    points: [
      { timestamp: '2026-01-10T00:00:00Z', value: 2400, contributions: 0, session: 'REGULAR' },
      { timestamp: '2026-01-10T00:05:00Z', value: 2450, contributions: 0, session: 'REGULAR' },
      { timestamp: '2026-01-10T00:07:00Z', value: 2500, contributions: 0, session: 'REGULAR' }
    ]
  };

  let service: AccountValuationService;
  let streamPrices: ReturnType<typeof signal<Record<string, number>>>;
  let settledPrices: ReturnType<typeof signal<Record<string, number>>>;
  let portfolioApi: MockedObject<PortfolioService>;
  let holdingsApi: MockedObject<HoldingsService>;
  let balanceApi: MockedObject<BalanceService>;
  let marketApi: MockedObject<MarketDataService>;

  beforeEach(() => {
    streamPrices = signal<Record<string, number>>({});
    settledPrices = signal<Record<string, number>>({});
    portfolioApi = createSpyObj('PortfolioService', ['getHistory']);
    portfolioApi.getHistory.mockReturnValue(of(dayHistory));
    holdingsApi = createSpyObj('HoldingsService', ['getHoldings', 'getAccounts']);
    balanceApi = createSpyObj('BalanceService', ['getBalance']);
    marketApi = createSpyObj('MarketDataService',
      ['getAllLatestPrices', 'getPreviousClose', 'getIntradayCloses']);
    holdingsApi.getHoldings.mockReturnValue(of(holdings));
    holdingsApi.getAccounts.mockReturnValue(of(summaries));
    balanceApi.getBalance.mockReturnValue(of(balance));
    marketApi.getAllLatestPrices.mockReturnValue(of([
      { symbol: 'AAPL', name: 'Apple Inc.', price: 150, asOf: '' },
      { symbol: 'SPX', name: null, price: 5000, asOf: '' }
    ]));
    marketApi.getPreviousClose.mockReturnValue(of(140));
    marketApi.getIntradayCloses.mockReturnValue(of([145, 150]));
    TestBed.configureTestingModule({
      providers: [
        AccountValuationService,
        { provide: HoldingsService, useValue: holdingsApi },
        { provide: BalanceService, useValue: balanceApi },
        { provide: MarketDataService, useValue: marketApi },
        { provide: PortfolioService, useValue: portfolioApi },
        { provide: PriceStreamService, useValue: { prices: streamPrices, settledPrices } }
      ]
    });
    service = TestBed.inject(AccountValuationService);
  });

  it('is loading, with no accounts, until the first load completes', () => {
    expect(service.accountsLoading()).toBe(true);
    expect(service.accounts()).toEqual([]);
    expect(service.multiAccount()).toBe(false);
  });

  it('values every account from balances, positions and prices', () => {
    const loaded = vi.fn().mockName('loaded');
    service.load(loaded);

    expect(loaded).toHaveBeenCalled();
    expect(service.accountsLoading()).toBe(false);
    expect(service.multiAccount()).toBe(true);
    expect(service.marketNames()).toEqual({ AAPL: 'Apple Inc.' });

    const [first, second] = service.accounts();
    expect(first.name).toBe('Brokerage 1');
    expect(first.status).toBe('ACTIVE');
    expect(first.cash).toBe(1000);
    expect(first.marketValue).toBe(1500);
    expect(first.value).toBe(2500);
    expect(first.dayChange).toBe(100);
    expect(first.totalReturn).toBe(500);
    expect(first.positionCount).toBe(1);
    // The account's own day so far, without the history's closing "now" point.
    expect(portfolioApi.getHistory).toHaveBeenCalledWith('1D', 'a1');
    expect(first.intraday).toEqual([2400, 2450]);
    expect(first.dayStartValue).toBe(2400);

    // An account with no saved summary has no status or number, and shares sum to one.
    expect(second.name).toBe('Brokerage ··0002');
    expect(second.status).toBeNull();
    expect(second.tradingEnabled).toBeNull();
    expect(first.share + second.share).toBeCloseTo(1);
  });

  it('prefers a live stream price over the REST snapshot', () => {
    service.load();
    streamPrices.set({ AAPL: 160 });
    expect(service.prices()['AAPL']).toBe(160);
    expect(service.priceHoldings(holdings.accounts[0].positions)[0].marketValue).toBe(1600);
  });

  it('values accounts at settled prices, so a total moves only when the stream settles', () => {
    service.load();
    streamPrices.set({ AAPL: 160 });
    expect(service.accounts()[0].marketValue).toBe(1500);
    settledPrices.set({ AAPL: 160 });
    expect(service.accounts()[0].marketValue).toBe(1600);
    expect(service.accounts()[0].dayChange).toBe(200);
  });

  it('measures shares bought today from what they cost, not from the previous close', () => {
    holdingsApi.getHoldings.mockReturnValue(of({ accounts: [
      { accountId: 'a1', accountNumber: 'ACC-0001', baseCurrency: 'USD', positions: [
        // The stock is up from 140 to 150 today, but these were bought at 152.
        { ...holdings.accounts[0].positions[0], quantity: 10, averageCost: 152, quantityAtOpen: 0, todayCashFlow: -1520 }
      ] }
    ] }));
    service.load();
    const [account] = service.accounts();
    expect(account.dayChange).toBe(-20);
    expect(account.dayChangePercent).toBeCloseTo((-20 / 1520) * 100);
    const [holding] = service.priceHoldings(service.holdingsResponse()!.accounts[0].positions);
    expect(holding.dayChange).toBe(-20);
  });

  it('adds shares bought today to those held at the open', () => {
    holdingsApi.getHoldings.mockReturnValue(of({ accounts: [
      { accountId: 'a1', accountNumber: 'ACC-0001', baseCurrency: 'USD', positions: [
        // 6 held at the 140 close, 4 more bought today at 145.
        { ...holdings.accounts[0].positions[0], quantity: 10, quantityAtOpen: 6, todayCashFlow: -580 }
      ] }
    ] }));
    service.load();
    // 6 x (150 - 140) + 4 x (150 - 145).
    expect(service.accounts()[0].dayChange).toBe(80);
    expect(service.accounts()[0].dayChangePercent).toBeCloseTo((80 / (840 + 580)) * 100);
  });

  it('counts what positions sold off today made before they were sold', () => {
    holdingsApi.getHoldings.mockReturnValue(of({ accounts: [
      { ...holdings.accounts[0], closedToday: [
        { instrumentId: 'i3', symbol: 'MSFT', instrumentName: 'Microsoft', assetClass: 'EQUITY', quantity: 0,
          averageCost: 0, quantityAtOpen: 3, todayCashFlow: 450 }
      ] }
    ] }));
    service.load();
    const [account] = service.accounts();
    expect(marketApi.getPreviousClose).toHaveBeenCalledWith('MSFT');
    // AAPL's 100, plus MSFT sold for 450 from a 3 x 140 close.
    expect(account.dayChange).toBe(100 + 30);
    expect(account.positionCount).toBe(1);
  });

  it('goes without an account\'s sparkline when its history fails', () => {
    portfolioApi.getHistory.mockReturnValue(throwError(() => ({ status: 503 })));
    service.load();
    expect(service.accounts()[0].intraday).toEqual([]);
    expect(service.accounts()[0].dayStartValue).toBeNull();
  });

  it('reports a zero share when nothing is worth anything', () => {
    balanceApi.getBalance.mockReturnValue(of({
      accounts: [{ accountId: 'a1', accountNumber: 'ACC-0001', currency: 'USD', balance: 0 }], totalsByCurrency: {}
    }));
    holdingsApi.getHoldings.mockReturnValue(of({ accounts: [] }));
    service.load();
    expect(service.accounts()[0].share).toBe(0);
    expect(service.accounts()[0].dayChange).toBeNull();
  });

  it('has no day change until previous closes arrive', () => {
    marketApi.getPreviousClose.mockReturnValue(new Subject<number | null>());
    service.load();
    expect(service.accounts()[0].dayChange).toBeNull();
    expect(service.accounts()[0].dayChangePercent).toBeNull();
  });

  it('keeps holdings without a price unvalued', () => {
    marketApi.getAllLatestPrices.mockReturnValue(of([]));
    service.load();
    const [holding] = service.priceHoldings(holdings.accounts[0].positions);
    expect(holding.price).toBeNull();
    expect(holding.marketValue).toBeNull();
    expect(holding.totalReturn).toBeNull();
    expect(holding.totalReturnPercent).toBeNull();
  });

  it('merges a symbol held in several accounts into one holding', () => {
    service.load();
    const merged = service.priceHoldings(service.holdingsResponse()!.accounts.flatMap(a => a.positions));
    const apple = merged.find(h => h.symbol === 'AAPL')!;
    expect(apple.quantity).toBe(15);
    expect(apple.averageCost).toBeCloseTo((10 * 100 + 5 * 140) / 15);
    expect(apple.totalReturnPercent).toBeCloseTo(((15 * 150 - 1700) / 1700) * 100);
  });

  it('sorts holdings largest first and tolerates no positions', () => {
    service.load();
    streamPrices.set({ AAPL: 10, MSFT: 1000 });
    const msft = { instrumentId: 'i3', symbol: 'MSFT', instrumentName: 'Microsoft', assetClass: 'EQUITY', quantity: 1, averageCost: 1 };
    const sorted = service.priceHoldings([holdings.accounts[0].positions[0], msft]);
    expect(sorted.map(h => h.symbol)).toEqual(['MSFT', 'AAPL']);
    expect(service.openPositions(undefined)).toEqual([]);
    expect(service.openPositions(holdings.accounts[0].positions).length).toBe(1);
  });

  it('lists each held symbol once', () => {
    expect(service.heldSymbols()).toEqual([]);
    service.load();
    expect(service.heldSymbols()).toEqual(['AAPL', 'GONE']);
  });

  it('fetches daily context once per symbol', () => {
    service.loadDailyContext(['AAPL'], true);
    service.loadDailyContext(['AAPL'], true);
    service.loadDailyContext(['MSFT'], false);
    expect(marketApi.getPreviousClose).toHaveBeenCalledTimes(2);
    expect(marketApi.getIntradayCloses).toHaveBeenCalledTimes(1);
    expect(service.previousCloses()).toEqual({ AAPL: 140, MSFT: 140 });
  });

  it('fetches the price snapshot only on the first load', () => {
    service.load();
    service.load();
    expect(marketApi.getAllLatestPrices).toHaveBeenCalledTimes(1);
    expect(holdingsApi.getHoldings).toHaveBeenCalledTimes(2);
  });

  it('keeps a live price when the snapshot arrives later', () => {
    const snapshot = new Subject<{ symbol: string; name: string | null; price: number; asOf: string }[]>();
    marketApi.getAllLatestPrices.mockReturnValue(snapshot);
    service.load();
    snapshot.next([{ symbol: 'AAPL', name: null, price: 1, asOf: '' }]);
    expect(service.prices()['AAPL']).toBe(1);
  });

  it('surfaces a load failure using the server message, or a fallback', () => {
    holdingsApi.getHoldings.mockReturnValue(throwError(() => ({ error: { message: 'Nope' } })));
    service.load();
    expect(service.accountError()).toBe('Nope');

    holdingsApi.getHoldings.mockReturnValue(throwError(() => ({})));
    service.load();
    expect(service.accountError()).toBe('We couldn’t load your accounts.');

    holdingsApi.getHoldings.mockReturnValue(of(holdings));
    service.load();
    expect(service.accountError()).toBeNull();
  });
});
