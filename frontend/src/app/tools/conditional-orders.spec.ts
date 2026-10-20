import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TRADE_PLAN_KEY, TradePlanService, conditionsLabel } from './trade-plan';
import { PlanSubmission } from './plan-submission';
import { tickInZone } from '../../testing/tick-in-zone';

describe('conditional orders in the trade plan', () => {
  const signIn = (email: string) => localStorage.setItem('auth_token',
    'header.' + btoa(JSON.stringify({ role: 'CLIENT', sid: 's', email })).replace(/=+$/, '') + '.signature');

  const bracketBuy = {
    symbol: 'AAPL', side: 'BUY' as const, quantity: 3, orderType: 'LIMIT' as const, limitPrice: 180, timeInForce: 'GTC' as const,
    bracket: { takeProfit: 210, stopLoss: 170 }
  };

  let plan: TradePlanService;
  let submission: PlanSubmission;
  let http: HttpTestingController;

  function create(): void {
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [TradePlanService, PlanSubmission, provideHttpClient(), provideHttpClientTesting()]
    });
    plan = TestBed.inject(TradePlanService);
    submission = TestBed.inject(PlanSubmission);
    http = TestBed.inject(HttpTestingController);
    tickInZone();
  }

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

  it('keeps an order\'s type, prices, time in force and bracket', () => {
    plan.addPlan('draw', 'a1', [bracketBuy]);
    const { id, source, ...order } = plan.orders()[0];
    expect(source).toBe('draw');
    expect(order).toEqual(bracketBuy);
  });

  it('leaves out orders order-app would refuse, and adds nothing extra to a plain one', () => {
    plan.addPlan('draw', 'a1', [
      { symbol: 'AAPL', side: 'SELL', quantity: 1, bracket: { takeProfit: 2, stopLoss: 1 } },
      { symbol: 'MSFT', side: 'BUY', quantity: 1, orderType: 'LIMIT' },
      { symbol: 'NVDA', side: 'BUY', quantity: 1 }
    ]);
    expect(plan.orders().map(({ id, ...o }) => o)).toEqual([{ source: 'draw', symbol: 'NVDA', side: 'BUY', quantity: 1 }]);
  });

  it('survives a refresh, and drops a stored order whose conditions were tampered with', () => {
    plan.addPlan('draw', 'a1', [bracketBuy]);
    tickInZone();
    const stored = JSON.parse(sessionStorage.getItem(TRADE_PLAN_KEY)!);
    stored.orders.push({ ...stored.orders[0], id: 'o9', bracket: { takeProfit: 1, stopLoss: 5 } });
    sessionStorage.setItem(TRADE_PLAN_KEY, JSON.stringify(stored));
    create();
    expect(plan.orders().length).toBe(1);
    expect(plan.orders()[0].bracket).toEqual({ takeProfit: 210, stopLoss: 170 });
  });

  it('never nets a conditional order', () => {
    plan.addPlan('draw', 'a1', [bracketBuy]);
    plan.addPlan('rebalance', 'a1', [{ symbol: 'AAPL', side: 'SELL', quantity: 2 }]);
    expect(plan.conflicts()).toEqual([]);

    plan.addPlan('basket', 'a1', [{ symbol: 'AAPL', side: 'BUY', quantity: 5 }]);
    expect(plan.conflicts()).toEqual(['AAPL']);
    plan.net('AAPL');
    const left = plan.sorted().map(o => `${o.source} ${o.side} ${o.quantity} ${o.orderType ?? 'MARKET'}`);
    expect(left).toEqual(['draw BUY 3 LIMIT', 'netted BUY 3 MARKET']);
  });

  it('keeps conditions when the quantity is edited', () => {
    plan.addPlan('draw', 'a1', [bracketBuy]);
    plan.setQuantity(plan.orders()[0].id, 7);
    expect(plan.orders()[0]).toEqual(expect.objectContaining({ quantity: 7, limitPrice: 180, bracket: bracketBuy.bracket }));
  });

  it('describes an order\'s terms in a few words', () => {
    expect(conditionsLabel({})).toBe('');
    expect(conditionsLabel(bracketBuy)).toBe('Limit $180.00 · GTC · Take profit $210.00 / stop loss $170.00');
    expect(conditionsLabel({ orderType: 'STOP_LIMIT', stopPrice: 170, limitPrice: 169 })).toBe('Stop $170.00 → limit $169.00');
  });

  it('sends a conditional order\'s terms, without the market-only quoted price', async () => {
    const done = submission.submit('a1', [{ id: 'o1', ...bracketBuy, quotedPrice: 180 }], 1000, 0);
    await Promise.resolve();
    const request = http.expectOne('/api/order/orders');
    expect(request.request.body).toEqual({
      accountId: 'a1', symbol: 'AAPL', side: 'BUY', quantity: 3, orderType: 'LIMIT', limitPrice: 180, timeInForce: 'GTC',
      bracketTakeProfitPrice: 210, bracketStopLossPrice: 170
    });
    request.flush({ orderId: 'x', accountId: 'a1', symbol: 'AAPL', side: 'BUY', quantity: 3, status: 'OPEN', reservedCash: 540 });
    await done;
    expect(submission.rows()[0].status).toBe('open');
    http.verify();
  });
});
