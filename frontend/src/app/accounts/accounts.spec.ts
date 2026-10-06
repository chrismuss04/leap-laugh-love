import { signal } from '@angular/core';
import { ComponentFixture, TestBed, fakeAsync, tick } from '@angular/core/testing';
import { ActivatedRoute, Router, convertToParamMap, provideRouter } from '@angular/router';
import { BehaviorSubject, of, throwError } from 'rxjs';
import { BalanceService } from '../services/balance';
import { HoldingsService } from '../services/holdings';
import { MarketDataService } from '../services/market-data';
import { PriceStreamService } from '../services/price-stream';
import { AccountsComponent } from './accounts';

describe('AccountsComponent', () => {
  const holdings = {
    accounts: [
      { accountId: 'a1', accountNumber: 'ACC-0001', baseCurrency: 'USD', positions: [
        { instrumentId: 'i', symbol: 'AAPL', instrumentName: 'Apple', assetClass: 'EQUITY', quantity: 10, averageCost: 100 }] },
      { accountId: 'a2', accountNumber: 'ACC-0002', baseCurrency: 'USD', positions: [] }
    ]
  };
  const balance = {
    accounts: [
      { accountId: 'a1', accountNumber: 'ACC-0001', currency: 'USD', balance: 1000 },
      { accountId: 'a2', accountNumber: 'ACC-0002', currency: 'USD', balance: 500 }
    ],
    totalsByCurrency: {}
  };

  let params: BehaviorSubject<ReturnType<typeof convertToParamMap>>;
  let fixture: ComponentFixture<AccountsComponent>;
  let page: AccountsComponent;
  let holdingsApi: jasmine.SpyObj<HoldingsService>;
  let stream: { prices: ReturnType<typeof signal<Record<string, number>>>; watch: jasmine.Spy };

  function create(accountId: string | null = null): void {
    params.next(convertToParamMap(accountId ? { accountId } : {}));
    fixture = TestBed.createComponent(AccountsComponent);
    page = fixture.componentInstance;
    fixture.detectChanges();
  }

  beforeEach(() => {
    params = new BehaviorSubject(convertToParamMap({}));
    holdingsApi = jasmine.createSpyObj('HoldingsService', ['getHoldings', 'getAccounts']);
    const balanceApi = jasmine.createSpyObj('BalanceService', ['getBalance']);
    const marketApi = jasmine.createSpyObj('MarketDataService',
      ['getAllLatestPrices', 'getPreviousClose', 'getIntradayCloses']);
    stream = { prices: signal<Record<string, number>>({}), watch: jasmine.createSpy('watch') };
    holdingsApi.getHoldings.and.returnValue(of(holdings));
    holdingsApi.getAccounts.and.returnValue(of([]));
    balanceApi.getBalance.and.returnValue(of(balance));
    marketApi.getAllLatestPrices.and.returnValue(of([{ symbol: 'AAPL', name: 'Apple', price: 150, asOf: '' }]));
    marketApi.getPreviousClose.and.returnValue(of(140));
    marketApi.getIntradayCloses.and.returnValue(of([145, 150]));

    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        { provide: ActivatedRoute, useValue: { paramMap: params.asObservable() } },
        { provide: HoldingsService, useValue: holdingsApi },
        { provide: BalanceService, useValue: balanceApi },
        { provide: MarketDataService, useValue: marketApi },
        { provide: PriceStreamService, useValue: stream }
      ]
    });
    // The panels are covered by their own specs; this one drives the page's own logic.
    TestBed.overrideComponent(AccountsComponent, { set: { imports: [], template: '' } });
  });

  it('loads the accounts and totals their cash', () => {
    create();
    expect(page.loading()).toBeFalse();
    expect(page.accounts().length).toBe(2);
    expect(page.totalCash()).toBe(1500);
    expect(page.multiAccount()).toBeTrue();
    expect(page.cashAccounts()).toEqual([
      { accountId: 'a1', name: 'Brokerage ··0001', cash: 1000 },
      { accountId: 'a2', name: 'Brokerage ··0002', cash: 500 }
    ]);
    expect(page.selectedAccount()).toBeNull();
    expect(page.rail()).toBe('cash');
  });

  it('keeps the stream on every held symbol once loaded', () => {
    create();
    expect(stream.watch).toHaveBeenCalledWith(['AAPL']);
  });

  it('is about one account when the route names it', () => {
    create('a2');
    expect(page.selectedAccount()?.accountId).toBe('a2');
  });

  it('falls back to the list for an account that is not the client\'s', () => {
    const navigate = spyOn(TestBed.inject(Router), 'navigate').and.resolveTo(true);
    create('someone-elses');
    expect(navigate).toHaveBeenCalledWith(['/accounts'], { replaceUrl: true });
  });

  it('shows a load failure from the shared valuation', () => {
    holdingsApi.getHoldings.and.returnValue(throwError(() => ({ error: { message: 'Down' } })));
    create();
    expect(page.error()).toBe('Down');
    expect(page.loading()).toBeTrue();
  });

  it('reloads on demand and after an account is opened', () => {
    create();
    holdingsApi.getHoldings.calls.reset();
    page.load();
    page.onOpened({} as never);
    expect(holdingsApi.getHoldings).toHaveBeenCalledTimes(2);
  });

  it('formats a share as a whole percent', () => {
    create();
    expect(page.percent(0.256)).toBe('26%');
    expect(page.trackById(0, page.accounts()[0])).toBe('a1');
  });

  it('starts a transfer or a deposit on the cash panel', fakeAsync(() => {
    create();
    const panel = jasmine.createSpyObj('CashPanelComponent', ['startTransfer', 'startDeposit']);
    page.cashPanel = panel;
    page.rail.set('open');

    page.transferFrom('a1');
    expect(page.rail()).toBe('cash');
    tick();
    expect(panel.startTransfer).toHaveBeenCalledWith('a1');

    page.addCash('a2');
    tick();
    expect(panel.startDeposit).toHaveBeenCalledWith('a2');
  }));

  it('tolerates a missing cash panel', fakeAsync(() => {
    create();
    page.cashPanel = undefined;
    page.addCash('a1');
    tick();
    expect(page.rail()).toBe('cash');
  }));

  it('switches the rail to the open-account panel', () => {
    create();
    page.openNew();
    expect(page.rail()).toBe('open');
  });

  it('goes to a new account to fund it', () => {
    const navigate = spyOn(TestBed.inject(Router), 'navigate').and.resolveTo(true);
    create();
    page.fund('new');
    expect(navigate).toHaveBeenCalledWith(['/accounts', 'new']);
  });
});
