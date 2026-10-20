import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { AuthService } from '../services/auth.service';
import { TRADE_PLAN_KEY, TradePlanService, sourceLabel } from './trade-plan';

describe('TradePlanService', () => {
  const signIn = (email: string) => localStorage.setItem('auth_token',
    'header.' + btoa(JSON.stringify({ role: 'CLIENT', sid: 's', email })).replace(/=+$/, '') + '.signature');

  let plan: TradePlanService;

  function create(): TradePlanService {
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [TradePlanService, provideHttpClient(), provideHttpClientTesting()]
    });
    plan = TestBed.inject(TradePlanService);
    TestBed.tick();
    return plan;
  }

  const lines = () => plan.sorted().map(o => `${o.side} ${o.quantity} ${o.symbol} (${sourceLabel(o.source)})`);

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
    it('counts each arrival of orders, but not an add with none', () => {
      expect(plan.arrivals()).toBe(0);
      plan.addPlan('rebalance', 'a1', [{ symbol: 'MSFT', side: 'BUY', quantity: 2 }]);
      plan.addPlan('rebalance', 'a1', [{ symbol: 'MSFT', side: 'BUY', quantity: 2 }]);
      plan.addPlan('basket', 'a1', []);
      plan.setQuantity(plan.orders()[0].id, 3);
      expect(plan.arrivals()).toBe(2);
    });

    it('adds a tool\'s orders tagged with the tool, sells first', () => {
      plan.addPlan('rebalance', 'a1', [{ symbol: 'MSFT', side: 'BUY', quantity: 2 }, { symbol: 'AAPL', side: 'SELL', quantity: 5 }]);
      expect(lines()).toEqual(['SELL 5 AAPL (Rebalance)', 'BUY 2 MSFT (Rebalance)']);
      expect(plan.accountId()).toBe('a1');
      expect(plan.count()).toBe(2);
      expect(plan.announcement()).toBe('2 orders from Rebalance added to your plan.');
    });

    it('replaces a tool\'s earlier orders instead of duplicating them', () => {
      plan.addPlan('rebalance', 'a1', [{ symbol: 'AAPL', side: 'SELL', quantity: 5 }]);
      plan.addPlan('basket', 'a1', [{ symbol: 'NVDA', side: 'BUY', quantity: 1 }]);
      plan.addPlan('rebalance', 'a1', [{ symbol: 'AAPL', side: 'SELL', quantity: 3 }]);
      expect(lines()).toEqual(['SELL 3 AAPL (Rebalance)', 'BUY 1 NVDA (Basket)']);
      expect(plan.announcement()).toBe('Rebalance\'s orders were replaced: 1 order in your plan from Rebalance.');
    });

    it('removes a tool\'s orders when it adds an empty plan', () => {
      plan.addPlan('draw', 'a1', [{ symbol: 'AAPL', side: 'BUY', quantity: 1 }]);
      plan.addPlan('draw', 'a1', []);
      expect(plan.isEmpty()).toBe(true);
      expect(plan.announcement()).toBe('Draw\'s orders were removed from your plan.');
      plan.addPlan('draw', 'a1', []);
      expect(plan.announcement()).toBe('Draw had no orders to add.');
    });

    it('leaves out orders of less than one whole share', () => {
      plan.addPlan('basket', 'a1', [
        { symbol: 'AAPL', side: 'BUY', quantity: 0 }, { symbol: 'MSFT', side: 'BUY', quantity: 1.5 },
        { symbol: 'NVDA', side: 'BUY', quantity: 1 }
      ]);
      expect(lines()).toEqual(['BUY 1 NVDA (Basket)']);
    });

    it('starts over for a plan on another account', () => {
      plan.addPlan('basket', 'a1', [{ symbol: 'AAPL', side: 'BUY', quantity: 1 }]);
      plan.addPlan('rebalance', 'a2', [{ symbol: 'MSFT', side: 'BUY', quantity: 1 }]);
      expect(plan.accountId()).toBe('a2');
      expect(lines()).toEqual(['BUY 1 MSFT (Rebalance)']);
    });
  });

  describe('editing', () => {
    beforeEach(() => plan.addPlan('basket', 'a1', [
      { symbol: 'AAPL', side: 'BUY', quantity: 2 }, { symbol: 'MSFT', side: 'BUY', quantity: 1 }
    ]));
    const id = (symbol: string) => plan.orders().find(o => o.symbol === symbol)!.id;

    it('removes one order', () => {
      plan.remove(id('AAPL'));
      expect(lines()).toEqual(['BUY 1 MSFT (Basket)']);
      expect(plan.announcement()).toBe('Removed buy 2 AAPL from your plan.');
      plan.remove('missing');
      expect(plan.count()).toBe(1);
    });

    it('clears the whole tray', () => {
      plan.clear();
      expect(plan.isEmpty()).toBe(true);
      expect(plan.announcement()).toBe('Your plan was cleared.');
    });

    it('changes a quantity', () => {
      expect(plan.setQuantity(id('AAPL'), 7)).toBeNull();
      expect(lines()).toEqual(['BUY 7 AAPL (Basket)', 'BUY 1 MSFT (Basket)']);
    });

    for (const value of [0, -2, 1.5, Number.NaN]) {
      it(`refuses ${value} shares and keeps the quantity`, () => {
        expect(plan.setQuantity(id('AAPL'), value)).toBe('Enter a whole number of shares, 1 or more.');
        expect(plan.orders().find(o => o.symbol === 'AAPL')!.quantity).toBe(2);
      });
    }
  });

  describe('netting', () => {
    it('flags a symbol bought by one tool and sold by another', () => {
      plan.addPlan('basket', 'a1', [{ symbol: 'AAPL', side: 'BUY', quantity: 5 }, { symbol: 'MSFT', side: 'BUY', quantity: 1 }]);
      plan.addPlan('raise-cash', 'a1', [{ symbol: 'AAPL', side: 'SELL', quantity: 3 }, { symbol: 'MSFT', side: 'BUY', quantity: 1 }]);
      expect(plan.conflicts()).toEqual(['AAPL']);
    });

    it('nets opposite orders into one, tagged as netted', () => {
      plan.addPlan('basket', 'a1', [{ symbol: 'AAPL', side: 'BUY', quantity: 5 }]);
      plan.addPlan('raise-cash', 'a1', [{ symbol: 'AAPL', side: 'SELL', quantity: 3 }, { symbol: 'NVDA', side: 'SELL', quantity: 1 }]);
      plan.net('AAPL');
      expect(lines()).toEqual(['SELL 1 NVDA (Raise Cash)', 'BUY 2 AAPL (Netted)']);
      expect(plan.conflicts()).toEqual([]);
      expect(plan.announcement()).toBe('The AAPL orders were netted into one: buy 2.');
    });

    it('nets to a sell when the sells are bigger', () => {
      plan.addPlan('basket', 'a1', [{ symbol: 'AAPL', side: 'BUY', quantity: 1 }]);
      plan.addPlan('rebalance', 'a1', [{ symbol: 'AAPL', side: 'SELL', quantity: 4 }]);
      plan.net('AAPL');
      expect(lines()).toEqual(['SELL 3 AAPL (Netted)']);
    });

    it('removes orders that cancel out', () => {
      plan.addPlan('basket', 'a1', [{ symbol: 'AAPL', side: 'BUY', quantity: 2 }]);
      plan.addPlan('rebalance', 'a1', [{ symbol: 'AAPL', side: 'SELL', quantity: 2 }]);
      plan.net('AAPL');
      expect(plan.isEmpty()).toBe(true);
      expect(plan.announcement()).toBe('The AAPL orders cancel out, so they were removed from your plan.');
      plan.net('AAPL');
      expect(plan.isEmpty()).toBe(true);
    });

    it('flags the conflict again when a tool re-adds after netting', () => {
      plan.addPlan('basket', 'a1', [{ symbol: 'AAPL', side: 'BUY', quantity: 5 }]);
      plan.addPlan('rebalance', 'a1', [{ symbol: 'AAPL', side: 'SELL', quantity: 3 }]);
      plan.net('AAPL');
      plan.addPlan('rebalance', 'a1', [{ symbol: 'AAPL', side: 'SELL', quantity: 1 }]);
      expect(lines()).toEqual(['SELL 1 AAPL (Rebalance)', 'BUY 2 AAPL (Netted)']);
      expect(plan.conflicts()).toEqual(['AAPL']);
    });
  });

  describe('account', () => {
    it('lets an empty plan follow the account, but not one with orders', () => {
      plan.adopt('a1');
      expect(plan.accountId()).toBe('a1');
      plan.addPlan('basket', 'a1', [{ symbol: 'AAPL', side: 'BUY', quantity: 1 }]);
      plan.adopt('a2');
      expect(plan.accountId()).toBe('a1');
    });
  });

  describe('persistence', () => {
    it('survives a reload within the session', () => {
      plan.addPlan('basket', 'a1', [{ symbol: 'AAPL', side: 'BUY', quantity: 2 }]);
      TestBed.tick();
      create();
      expect(plan.accountId()).toBe('a1');
      expect(lines()).toEqual(['BUY 2 AAPL (Basket)']);
      // New orders don't reuse a restored order's id.
      plan.addPlan('draw', 'a1', [{ symbol: 'MSFT', side: 'BUY', quantity: 1 }]);
      expect(new Set(plan.orders().map(o => o.id)).size).toBe(2);
    });

    it('ignores another client\'s plan from the same tab', () => {
      plan.addPlan('basket', 'a1', [{ symbol: 'AAPL', side: 'BUY', quantity: 2 }]);
      TestBed.tick();
      signIn('grace@example.com');
      create();
      expect(plan.isEmpty()).toBe(true);
      expect(plan.accountId()).toBeNull();
    });

    it('drops anything malformed', () => {
      sessionStorage.setItem(TRADE_PLAN_KEY, JSON.stringify({
        owner: 'ada@example.com', accountId: 'a1',
        orders: [
          { id: 'o4', source: 'basket', symbol: 'AAPL', side: 'BUY', quantity: 1 },
          { id: 'o5', source: 'elsewhere', symbol: 'AAPL', side: 'BUY', quantity: 1 },
          { id: 'o6', source: 'basket', symbol: 'AAPL', side: 'HOLD', quantity: 1 },
          { id: 'o7', source: 'basket', symbol: 'AAPL', side: 'BUY', quantity: 0 },
          null
        ]
      }));
      create();
      expect(plan.orders().map(o => o.id)).toEqual(['o4']);

      sessionStorage.setItem(TRADE_PLAN_KEY, '{not json');
      expect(create().isEmpty()).toBe(true);
      sessionStorage.setItem(TRADE_PLAN_KEY, JSON.stringify({ owner: 'ada@example.com', accountId: 7, orders: 'none' }));
      expect(create().isEmpty()).toBe(true);
    });

    it('is cleared on sign-out', () => {
      plan.addPlan('basket', 'a1', [{ symbol: 'AAPL', side: 'BUY', quantity: 2 }]);
      TestBed.tick();
      expect(sessionStorage.getItem(TRADE_PLAN_KEY)).not.toBeNull();
      sessionStorage.setItem('unrelated', 'kept');

      TestBed.inject(AuthService).logout();
      TestBed.inject(HttpTestingController).match(() => true).forEach(request => request.flush(null));
      expect(sessionStorage.getItem(TRADE_PLAN_KEY)).toBeNull();
      expect(sessionStorage.getItem('unrelated')).toBe('kept');
    });

    it('is cleared when another tab signs out', () => {
      plan.addPlan('basket', 'a1', [{ symbol: 'AAPL', side: 'BUY', quantity: 2 }]);
      TestBed.tick();
      TestBed.inject(AuthService);
      localStorage.removeItem('auth_token');
      window.dispatchEvent(new StorageEvent('storage', { key: 'auth_token' }));
      expect(sessionStorage.getItem(TRADE_PLAN_KEY)).toBeNull();
    });
  });
});
