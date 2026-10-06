import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Subject, of, throwError } from 'rxjs';
import { AccountValuationService } from './account-valuation';
import { BalanceService } from './balance';
import { HoldingsService } from './holdings';
import { MarketDataService } from './market-data';
import { PriceStreamService } from './price-stream';

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
      maxSlippagePercent: null, createdAt: '2024-01-01T00:00:00Z', inactiveSince: null }
  ];

  let service: AccountValuationService;
  let streamPrices: ReturnType<typeof signal<Record<string, number>>>;
  let holdingsApi: jasmine.SpyObj<HoldingsService>;
  let balanceApi: jasmine.SpyObj<BalanceService>;
  let marketApi: jasmine.SpyObj<MarketDataService>;

  beforeEach(() => {
    streamPrices = signal<Record<string, number>>({});
    holdingsApi = jasmine.createSpyObj('HoldingsService', ['getHoldings', 'getAccounts']);
    balanceApi = jasmine.createSpyObj('BalanceService', ['getBalance']);
    marketApi = jasmine.createSpyObj('MarketDataService',
      ['getAllLatestPrices', 'getPreviousClose', 'getIntradayCloses']);
    holdingsApi.getHoldings.and.returnValue(of(holdings));
    holdingsApi.getAccounts.and.returnValue(of(summaries));
    balanceApi.getBalance.and.returnValue(of(balance));
    marketApi.getAllLatestPrices.and.returnValue(of([
      { symbol: 'AAPL', name: 'Apple Inc.', price: 150, asOf: '' },
      { symbol: 'SPX', name: null, price: 5000, asOf: '' }
    ]));
    marketApi.getPreviousClose.and.returnValue(of(140));
    marketApi.getIntradayCloses.and.returnValue(of([145, 150]));
    TestBed.configureTestingModule({
      providers: [
        AccountValuationService,
        { provide: HoldingsService, useValue: holdingsApi },
        { provide: BalanceService, useValue: balanceApi },
        { provide: MarketDataService, useValue: marketApi },
        { provide: PriceStreamService, useValue: { prices: streamPrices } }
      ]
    });
    service = TestBed.inject(AccountValuationService);
  });

  it('is loading, with no accounts, until the first load completes', () => {
    expect(service.accountsLoading()).toBeTrue();
    expect(service.accounts()).toEqual([]);
    expect(service.multiAccount()).toBeFalse();
  });

  it('values every account from balances, positions and prices', () => {
    const loaded = jasmine.createSpy('loaded');
    service.load(loaded);

    expect(loaded).toHaveBeenCalled();
    expect(service.accountsLoading()).toBeFalse();
    expect(service.multiAccount()).toBeTrue();
    expect(service.marketNames()).toEqual({ AAPL: 'Apple Inc.' });

    const [first, second] = service.accounts();
    expect(first.name).toBe('Brokerage ··0001');
    expect(first.status).toBe('ACTIVE');
    expect(first.cash).toBe(1000);
    expect(first.marketValue).toBe(1500);
    expect(first.value).toBe(2500);
    expect(first.dayChange).toBe(100);
    expect(first.totalReturn).toBe(500);
    expect(first.positionCount).toBe(1);
    expect(first.intraday).toEqual([1000 + 1450, 1000 + 1500]);
    expect(first.previousCloseValue).toBe(1000 + 1400);

    // An account with no saved summary has no status, and shares sum to one.
    expect(second.status).toBeNull();
    expect(second.tradingEnabled).toBeNull();
    expect(first.share + second.share).toBeCloseTo(1);
  });

  it('prefers a live stream price over the REST snapshot', () => {
    service.load();
    streamPrices.set({ AAPL: 160 });
    expect(service.prices()['AAPL']).toBe(160);
    expect(service.accounts()[0].marketValue).toBe(1600);
  });

  it('reports a zero share when nothing is worth anything', () => {
    balanceApi.getBalance.and.returnValue(of({
      accounts: [{ accountId: 'a1', accountNumber: 'ACC-0001', currency: 'USD', balance: 0 }], totalsByCurrency: {}
    }));
    holdingsApi.getHoldings.and.returnValue(of({ accounts: [] }));
    service.load();
    expect(service.accounts()[0].share).toBe(0);
    expect(service.accounts()[0].dayChange).toBeNull();
    expect(service.accounts()[0].intraday).toEqual([]);
  });

  it('has no day change until previous closes arrive', () => {
    marketApi.getPreviousClose.and.returnValue(new Subject<number | null>());
    service.load();
    expect(service.accounts()[0].dayChange).toBeNull();
    expect(service.accounts()[0].previousCloseValue).toBeNull();
  });

  it('keeps holdings without a price unvalued', () => {
    marketApi.getAllLatestPrices.and.returnValue(of([]));
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
    marketApi.getAllLatestPrices.and.returnValue(snapshot);
    service.load();
    snapshot.next([{ symbol: 'AAPL', name: null, price: 1, asOf: '' }]);
    expect(service.prices()['AAPL']).toBe(1);
  });

  it('surfaces a load failure using the server message, or a fallback', () => {
    holdingsApi.getHoldings.and.returnValue(throwError(() => ({ error: { message: 'Nope' } })));
    service.load();
    expect(service.accountError()).toBe('Nope');

    holdingsApi.getHoldings.and.returnValue(throwError(() => ({})));
    service.load();
    expect(service.accountError()).toBe('We couldn’t load your accounts.');

    holdingsApi.getHoldings.and.returnValue(of(holdings));
    service.load();
    expect(service.accountError()).toBeNull();
  });
});
