import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { AccountsService } from './accounts';
import { BalanceService } from './balance';
import { ClientRegistrationService, RegistrationRequest } from './client-registration.service';
import { HoldingsService } from './holdings';
import { MarketDataService } from './market-data';
import { PortfolioService } from './portfolio';

describe('HTTP services', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    http = TestBed.inject(HttpTestingController);
  });
  afterEach(() => http.verify());

  const failure = { status: 500, statusText: 'err' };
  const historyUrl = (r: { url: string }) => r.url === '/api/marketdata/prices/AAPL/history';

  describe('AccountsService', () => {
    it('lists, opens and funds accounts and saves trade settings', () => {
      const service = TestBed.inject(AccountsService);
      service.getAccounts().subscribe();
      http.expectOne('/api/account/accounts').flush([]);

      service.openAccount({ baseCurrency: 'USD' }).subscribe();
      const open = http.expectOne('/api/account/accounts');
      expect(open.request.method).toBe('POST');
      expect(open.request.body).toEqual({ baseCurrency: 'USD' });
      open.flush({});

      service.transferCash({ fromAccountId: 'a', toAccountId: 'b', amount: 5 }).subscribe();
      const transfer = http.expectOne('/api/account/balance/transfers');
      expect(transfer.request.method).toBe('POST');
      transfer.flush({});

      service.updateTradeSettings('a/1', 2.5).subscribe();
      const settings = http.expectOne('/api/account/accounts/a%2F1/trade-settings');
      expect(settings.request.method).toBe('PUT');
      expect(settings.request.body).toEqual({ maxSlippagePercent: 2.5 });
      settings.flush({});
    });
  });

  describe('BalanceService', () => {
    it('reads balances and deposits into an account', () => {
      const service = TestBed.inject(BalanceService);
      service.getBalance().subscribe();
      http.expectOne('/api/account/balance').flush({ accounts: [], totalsByCurrency: {} });

      service.deposit('acc-1', { amount: 25 }).subscribe();
      const deposit = http.expectOne('/api/account/balance/accounts/acc-1/deposit');
      expect(deposit.request.method).toBe('POST');
      expect(deposit.request.body).toEqual({ amount: 25 });
      deposit.flush({});
    });
  });

  describe('PortfolioService', () => {
    it('requests history for a range', () => {
      TestBed.inject(PortfolioService).getHistory('1M').subscribe();
      const request = http.expectOne(r => r.url === '/api/account/portfolio/history');
      expect(request.request.params.get('range')).toBe('1M');
      request.flush({});
    });
  });

  describe('ClientRegistrationService', () => {
    it('posts the registration request', () => {
      const body = { email: 'a@b.c' } as RegistrationRequest;
      TestBed.inject(ClientRegistrationService).register(body).subscribe();
      const request = http.expectOne(r => r.url.endsWith('/register'));
      expect(request.request.method).toBe('POST');
      expect(request.request.body).toBe(body);
      request.flush({ message: 'Check your email' });
    });

    it('posts the token from the emailed link to open the account', () => {
      TestBed.inject(ClientRegistrationService).verify('abc123').subscribe();
      const request = http.expectOne(r => r.url.endsWith('/register/verify'));
      expect(request.request.method).toBe('POST');
      expect(request.request.body).toEqual({ token: 'abc123' });
      request.flush(null);
    });
  });

  describe('HoldingsService', () => {
    it('returns no holdings without fetching positions when there are no accounts', () => {
      let result: unknown;
      TestBed.inject(HoldingsService).getHoldings().subscribe(r => result = r);
      http.expectOne('/api/account/accounts').flush([]);
      expect(result).toEqual({ accounts: [] });
    });

    it('fetches positions for every account', () => {
      let ids: string[] = [];
      TestBed.inject(HoldingsService).getHoldings().subscribe(r => ids = r.accounts.map(a => a.accountId));
      http.expectOne('/api/account/accounts').flush([{ accountId: 'a' }, { accountId: 'b' }]);
      http.expectOne('/api/account/accounts/a/positions').flush({ accountId: 'a', positions: [] });
      http.expectOne('/api/account/accounts/b/positions').flush({ accountId: 'b', positions: [] });
      expect(ids).toEqual(['a', 'b']);
    });

    it('lists accounts and one account positions', () => {
      const service = TestBed.inject(HoldingsService);
      service.getAccounts().subscribe();
      http.expectOne('/api/account/accounts').flush([]);
      service.getPositionsForAccount('x').subscribe();
      http.expectOne('/api/account/accounts/x/positions').flush({});
    });
  });

  describe('MarketDataService', () => {
    it('caches all latest prices', () => {
      const service = TestBed.inject(MarketDataService);
      service.getAllLatestPrices().subscribe();
      service.getAllLatestPrices().subscribe();
      http.expectOne('/api/marketdata/prices').flush([{ symbol: 'AAPL', price: 1 }]);
    });

    it('returns no prices on failure and retries on the next call', () => {
      const service = TestBed.inject(MarketDataService);
      let result: unknown;
      service.getAllLatestPrices().subscribe(r => result = r);
      http.expectOne('/api/marketdata/prices').flush({}, failure);
      expect(result).toEqual([]);
      service.getAllLatestPrices().subscribe();
      http.expectOne('/api/marketdata/prices').flush([]);
    });

    it('fetches a quote and a latest price with the symbol encoded', () => {
      const service = TestBed.inject(MarketDataService);
      service.getQuote('BRK/B').subscribe();
      http.expectOne('/api/marketdata/quotes/BRK%2FB').flush({});
      service.getLatestPrice('BRK/B').subscribe();
      http.expectOne('/api/marketdata/prices/BRK%2FB').flush({});
    });

    it('returns history oldest first', () => {
      let closes: number[] = [];
      TestBed.inject(MarketDataService)
        .getHistory('AAPL', new Date(0), new Date(1000), 300)
        .subscribe(candles => closes = candles.map(c => c.close));
      const request = http.expectOne(historyUrl);
      expect(request.request.params.get('interval')).toBe('300');
      expect(request.request.params.get('size')).toBe('1000');
      request.flush({ content: [{ close: 3 }, { close: 2 }, { close: 1 }], last: true });
      expect(closes).toEqual([1, 2, 3]);
    });

    it('reads the previous close, or null when absent or failing', () => {
      const service = TestBed.inject(MarketDataService);
      const results: (number | null)[] = [];
      service.getPreviousClose('AAPL').subscribe(r => results.push(r));
      http.expectOne(historyUrl).flush({ content: [{ close: 9 }], last: true });
      service.getPreviousClose('AAPL').subscribe(r => results.push(r));
      http.expectOne(historyUrl).flush({ content: [], last: true });
      service.getPreviousClose('AAPL').subscribe(r => results.push(r));
      http.expectOne(historyUrl).flush({}, failure);
      expect(results).toEqual([9, null, null]);
    });

    it('reads intraday closes, or none on failure', () => {
      const service = TestBed.inject(MarketDataService);
      const results: number[][] = [];
      service.getIntradayCloses('AAPL').subscribe(r => results.push(r));
      http.expectOne(historyUrl).flush({ content: [{ close: 2 }, { close: 1 }], last: true });
      service.getIntradayCloses('AAPL').subscribe(r => results.push(r));
      http.expectOne(historyUrl).flush({}, failure);
      expect(results).toEqual([[1, 2], []]);
    });
  });
});
