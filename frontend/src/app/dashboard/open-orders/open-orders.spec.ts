import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideZonelessChangeDetection } from '@angular/core';
import { OpenOrder } from '../../services/order';
import { OpenOrdersComponent } from './open-orders';

describe('OpenOrdersComponent', () => {
  let fixture: ComponentFixture<OpenOrdersComponent>;
  let component: OpenOrdersComponent;
  let http: HttpTestingController;

  const order = (overrides: Partial<OpenOrder> = {}): OpenOrder => ({
    orderId: 'o1', accountId: 'a1', symbol: 'AAPL', side: 'BUY', quantity: 2, status: 'OPEN', orderType: 'LIMIT',
    limitPrice: 95, stopPrice: null, timeInForce: 'GTC', submittedAt: '', expiresAt: null, triggeredAt: null,
    reservedCash: 190, ...overrides
  });

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [OpenOrdersComponent],
      providers: [provideHttpClient(), provideHttpClientTesting(), provideZonelessChangeDetection()]
    }).compileComponents();
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(OpenOrdersComponent);
    component = fixture.componentInstance;
    fixture.componentRef.setInput('prices', { AAPL: 100 });
    fixture.componentRef.setInput('orders', [order()]);
    fixture.detectChanges();
  });

  afterEach(() => http.verify());

  function text(): string {
    return (fixture.nativeElement.textContent as string).replace(/\s+/g, ' ');
  }

  function click(label: string): void {
    const found = Array.from<HTMLButtonElement>(fixture.nativeElement.querySelectorAll('button'))
      .find(b => b.textContent?.trim() === label);
    if (!found) throw new Error(`Missing button: ${label}`);
    found.click();
    fixture.detectChanges();
  }

  it('shows how far the market must move, and what the order holds', () => {
    expect(text()).toContain('Limit buy · 2');
    // No quote yet: measured from the last trade.
    expect(text()).toContain('AAPL needs to fall 5.0%');
    expect(text()).toContain('Until cancelled · $190.00 set aside');
    expect(component.rows()[0].proximity).toBeCloseTo(0.5);
  });

  it('says when a stop limit has been triggered and now waits on its limit', () => {
    fixture.componentRef.setInput('orders', [order({
      side: 'SELL', orderType: 'STOP_LIMIT', stopPrice: 101, limitPrice: 102, triggeredAt: '2026-10-12T12:00:00Z',
      reservedCash: null
    })]);
    fixture.detectChanges();
    expect(component.rows()[0].target).toBe(102);
    expect(text()).toContain('Stop reached · AAPL needs to rise 2.0%');
  });

  // Regression: the last trade reached a buy limit while the ask - what a buy fills at - was still
  // above it, and the card said "filling" for minutes before the order could fill.
  it('measures a buy from the ask, not the last trade', () => {
    fixture.componentRef.setInput('orders', [order({ limitPrice: 462.83 })]);
    fixture.componentRef.setInput('prices', { AAPL: 462.80 });
    fixture.componentRef.setInput('quoteOffsets', { AAPL: { ask: 0.12, bid: -0.12 } });
    fixture.detectChanges();
    expect(text()).toContain('Ask $462.92 · needs to fall 0.02%');
    expect(text()).not.toContain('filling');

    fixture.componentRef.setInput('prices', { AAPL: 462.70 });
    fixture.detectChanges();
    expect(text()).toContain('Ask at $462.82 · filling');
    expect(component.rows()[0].proximity).toBe(1);
  });

  it('measures a sell stop from the bid', () => {
    fixture.componentRef.setInput('orders', [order({ side: 'SELL', orderType: 'STOP', stopPrice: 99.9,
      limitPrice: null, reservedCash: null })]);
    fixture.componentRef.setInput('prices', { AAPL: 100 });
    fixture.componentRef.setInput('quoteOffsets', { AAPL: { ask: 0.05, bid: -0.05 } });
    fixture.detectChanges();
    expect(text()).toContain('Bid $99.95 · needs to fall 0.05%');
  });

  it('is hidden with no open orders', () => {
    fixture.componentRef.setInput('orders', []);
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('[data-testid="open-orders"]')).toBeNull();
  });

  it('cancels only after a second tap, and reports it', () => {
    const cancelled: OpenOrder[] = [];
    component.cancelled.subscribe(o => cancelled.push(o));

    click('Cancel');
    http.expectNone('/api/order/orders/o1/cancel');
    click('Keep');
    click('Cancel');
    click('Cancel order');

    const request = http.expectOne('/api/order/orders/o1/cancel');
    expect(request.request.method).toBe('POST');
    request.flush(order({ status: 'CANCELLED' }));
    fixture.detectChanges();
    expect(cancelled.map(o => o.orderId)).toEqual(['o1']);
  });

  it('explains an order that filled before it could be cancelled', () => {
    const cancelled: OpenOrder[] = [];
    component.cancelled.subscribe(o => cancelled.push(o));
    click('Cancel');
    click('Cancel order');
    http.expectOne('/api/order/orders/o1/cancel').flush(
      { message: 'This order has already executed and can no longer be cancelled' },
      { status: 409, statusText: 'Conflict' });
    fixture.detectChanges();
    expect(text()).toContain('already executed');
    // The dashboard reloads to show where it went.
    expect(cancelled.length).toBe(1);
  });
});
