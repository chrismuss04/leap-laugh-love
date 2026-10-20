import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { BASKET_KEY, BasketService, MAX_BASKET } from './basket';
import { tickInZone } from '../../../testing/tick-in-zone';

describe('BasketService', () => {
  const signIn = (email: string) => localStorage.setItem('auth_token',
    'header.' + btoa(JSON.stringify({ role: 'CLIENT', sid: 's', email })).replace(/=+$/, '') + '.signature');

  let basket: BasketService;

  function create(): BasketService {
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [BasketService, provideHttpClient(), provideHttpClientTesting()]
    });
    basket = TestBed.inject(BasketService);
    tickInZone();
    return basket;
  }

  /** Fills the basket with this many made-up stocks. */
  const fill = (count: number) => {
    for (let i = 0; i < count; i++) {
      basket.add(`S${i}`, `Stock ${i}`);
    }
  };

  beforeEach(() => {
    sessionStorage.clear();
    localStorage.clear();
    signIn('ada@example.com');
    create();
  });

  afterEach(() => {
    sessionStorage.clear();
    localStorage.clear();
  });

  describe('adding', () => {
    it('adds stocks in the order they come', () => {
      expect(basket.add('msft', 'Microsoft')).toBeNull();
      expect(basket.add('AAPL', 'Apple')).toBeNull();
      expect(basket.items()).toEqual([{ symbol: 'MSFT', name: 'Microsoft' }, { symbol: 'AAPL', name: 'Apple' }]);
      expect(basket.announcement()).toBe('Added AAPL. Your basket has 2 stocks.');
    });

    it('names a stock by its symbol when it has no name', () => {
      basket.add('AAPL', null);
      expect(basket.items()).toEqual([{ symbol: 'AAPL', name: 'AAPL' }]);
    });

    it('refuses a stock that is already in the basket', () => {
      basket.add('AAPL', 'Apple');
      expect(basket.reason('aapl')).toBe('Already in basket');
      expect(basket.add('aapl', 'Apple')).toBe('AAPL is already in your basket.');
      expect(basket.count()).toBe(1);
    });

    it('refuses instruments that cannot be traded', () => {
      expect(basket.reason('SPX')).toBe('Not tradable');
      expect(basket.add('BTCUSD', 'Bitcoin')).toBe('BTCUSD can\'t be traded, so it can\'t go in a basket.');
      expect(basket.isEmpty()).toBe(true);
    });

    it(`holds ${MAX_BASKET} stocks and refuses an 11th`, () => {
      fill(MAX_BASKET);
      expect(basket.isFull()).toBe(true);
      expect(basket.reason('AAPL')).toBe('Basket full');
      expect(basket.add('AAPL', 'Apple')).toBe('A basket holds up to 10 stocks. Remove one to add another.');
      expect(basket.count()).toBe(MAX_BASKET);
    });

    it('is ready to buy from 2 stocks', () => {
      basket.add('AAPL', 'Apple');
      expect(basket.ready()).toBe(false);
      basket.add('MSFT', 'Microsoft');
      expect(basket.ready()).toBe(true);
    });
  });

  describe('removing', () => {
    it('removes one stock and keeps the rest in order', () => {
      basket.add('AAPL', 'Apple');
      basket.add('MSFT', 'Microsoft');
      basket.add('NVDA', 'Nvidia');
      basket.remove('MSFT');
      expect(basket.symbols()).toEqual(['AAPL', 'NVDA']);
      expect(basket.announcement()).toBe('Removed MSFT. Your basket has 2 stocks.');
    });

    it('makes room again once a full basket loses a stock', () => {
      fill(MAX_BASKET);
      basket.remove('S0');
      expect(basket.add('AAPL', 'Apple')).toBeNull();
    });

    it('ignores a stock that is not in the basket', () => {
      basket.add('AAPL', 'Apple');
      basket.remove('MSFT');
      expect(basket.symbols()).toEqual(['AAPL']);
      expect(basket.announcement()).toBe('Added AAPL. Your basket has 1 stock.');
    });

    it('clears every stock', () => {
      fill(3);
      basket.clear();
      expect(basket.isEmpty()).toBe(true);
      expect(basket.announcement()).toBe('Your basket was cleared.');
    });
  });

  describe('the amount and its split', () => {
    beforeEach(() => {
      basket.add('AAPL', 'Apple');
      basket.add('MSFT', 'Microsoft');
    });

    it('starts with no amount', () => {
      expect(basket.totalCents()).toBeNull();
      expect(basket.amounts()).toEqual([0, 0]);
    });

    it('splits a new amount evenly, then keeps each stock\'s share when it changes', () => {
      basket.setTotal(100000);
      expect(basket.amounts()).toEqual([50000, 50000]);
      basket.setAmounts([75000, 25000]);
      basket.setTotal(200000);
      expect(basket.amounts()).toEqual([150000, 50000]);
    });

    it('splits evenly again when a stock comes or goes', () => {
      basket.setTotal(90000);
      basket.setAmounts([60000, 30000]);
      basket.add('NVDA', 'Nvidia');
      expect(basket.amounts()).toEqual([30000, 30000, 30000]);
      basket.remove('AAPL');
      expect(basket.amounts()).toEqual([45000, 45000]);
    });

    it('forgets the amount when the basket is cleared', () => {
      basket.setTotal(90000);
      basket.clear();
      expect(basket.totalCents()).toBeNull();
      expect(basket.amounts()).toEqual([]);
    });

    it('survives a refresh', () => {
      basket.setTotal(100000);
      basket.setAmounts([70000, 30000]);
      tickInZone();
      create();
      expect(basket.totalCents()).toBe(100000);
      expect(basket.amounts()).toEqual([70000, 30000]);
    });

    it('splits evenly a stored split that no longer adds up', () => {
      sessionStorage.setItem(BASKET_KEY, JSON.stringify({
        owner: 'ada@example.com',
        items: [{ symbol: 'AAPL', name: 'Apple' }, { symbol: 'MSFT', name: 'Microsoft' }],
        split: { totalCents: 100000, amounts: [70000, 20000] }
      }));
      create();
      expect(basket.amounts()).toEqual([50000, 50000]);
    });
  });

  describe('saving', () => {
    it('survives a refresh within the session', () => {
      basket.add('AAPL', 'Apple');
      basket.add('MSFT', 'Microsoft');
      tickInZone();
      create();
      expect(basket.items()).toEqual([{ symbol: 'AAPL', name: 'Apple' }, { symbol: 'MSFT', name: 'Microsoft' }]);
    });

    it('ignores another client\'s basket', () => {
      basket.add('AAPL', 'Apple');
      tickInZone();
      signIn('grace@example.com');
      create();
      expect(basket.isEmpty()).toBe(true);
    });

    it('drops anything stored that a basket couldn\'t hold', () => {
      const items = [
        { symbol: 'AAPL', name: 'Apple' }, { symbol: 'aapl', name: 'Apple again' }, { symbol: 'SPX', name: 'S&P 500' },
        { symbol: 42 }, null, ...Array.from({ length: 12 }, (_, i) => ({ symbol: `S${i}`, name: '' }))
      ];
      sessionStorage.setItem(BASKET_KEY, JSON.stringify({ owner: 'ada@example.com', items, split: { later: true } }));
      create();
      expect(basket.count()).toBe(MAX_BASKET);
      expect(basket.symbols().slice(0, 2)).toEqual(['AAPL', 'S0']);
    });

    it('starts empty when what was stored isn\'t readable', () => {
      sessionStorage.setItem(BASKET_KEY, '{not json');
      create();
      expect(basket.isEmpty()).toBe(true);
    });
  });
});
