import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideZonelessChangeDetection } from '@angular/core';
import { OpenOrder } from '../../services/order';
import { OpenOrdersComponent } from './open-orders';
import { MarketStatusService } from '../../services/market-status';
import { MarketSession } from '../../shared/market-session';

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

  describe('market hours', () => {
    function session(value: MarketSession): void {
      TestBed.inject(MarketStatusService).status.set({
        session: value, now: new Date().toISOString(), nextSession: null, nextTransitionAt: null,
        nextRegularOpen: '2026-10-16T13:30:00Z',
        today: { date: '2026-10-15', open: true, holiday: null, earlyClose: false,
          preMarketOpen: null, regularOpen: null, regularClose: null, afterHoursClose: null },
        mode: 'REAL', forced: false
      });
    }

    it('shows a queued market order waiting for the open, with its collar and no price bar', () => {
      session('CLOSED');
      fixture.componentRef.setInput('orders', [order({
        orderType: 'MARKET', limitPrice: null, timeInForce: 'DAY', reservedCash: 525, collarPrice: 105, queued: true
      })]);
      fixture.detectChanges();

      expect(text()).toContain('Queued Market buy · 2');
      expect(fixture.nativeElement.querySelector('[data-testid="queued-badge"]')).not.toBeNull();
      expect(text()).toContain('At open');
      expect(text()).toContain('Executes at the open');
      expect(text()).toContain('9:30 AM ET');
      expect(text()).toContain('up to $105.00');
      expect(text()).toContain('For the next open · $525.00 set aside');
      expect(fixture.nativeElement.querySelector('.progress')).toBeNull();
    });

    it('marks an extended-hours limit, and says when an order is waiting for market hours', () => {
      session('AFTER_HOURS');
      fixture.componentRef.setInput('orders', [
        order({ orderId: 'ext', extendedHours: true }),
        order({ orderId: 'reg', side: 'SELL', orderType: 'STOP', limitPrice: null, stopPrice: 101, reservedCash: null })
      ]);
      fixture.detectChanges();

      const rows = component.rows();
      expect(fixture.nativeElement.querySelectorAll('[data-testid="ext-badge"]').length).toBe(1);
      expect(rows[0].status).toBe('AAPL needs to fall 5.0%');
      // A sell stop at $101 with the price at $100 has been passed, but stops wait for regular hours.
      expect(rows[1].status).toBe('At its price · waiting for market hours');
    });

    it('does not offer to change a queued order\'s time in force', () => {
      session('CLOSED');
      fixture.componentRef.setInput('orders', [order({ orderType: 'MARKET', limitPrice: null, timeInForce: 'DAY', queued: true })]);
      fixture.detectChanges();
      click('Edit');
      expect(fixture.nativeElement.querySelector('[data-testid="edit-order"]').textContent).not.toContain('Good for');
    });
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

  describe('a bracket\'s linked exits', () => {
    const exit = (overrides: Partial<OpenOrder>) => order({
      side: 'SELL', reservedCash: null, quantity: 5, ocoGroupId: 'g1', parentOrderId: 'p1', ...overrides
    });

    it('lists the two legs together with an OCO badge, even when other orders sit between them', () => {
      fixture.componentRef.setInput('orders', [
        exit({ orderId: 'tp', orderType: 'LIMIT', limitPrice: 120 }),
        order({ orderId: 'other', symbol: 'AAPL' }),
        exit({ orderId: 'sl', orderType: 'STOP', limitPrice: null, stopPrice: 90 })
      ]);
      fixture.detectChanges();

      expect(component.rows().map(r => r.order.orderId)).toEqual(['tp', 'sl', 'other']);
      expect(component.rows().map(r => r.linked)).toEqual([true, true, false]);
      expect(fixture.nativeElement.querySelectorAll('[data-testid="oco-badge"]').length).toBe(2);
    });

    it('treats a lone leg, whose sibling is gone, as a normal order', () => {
      fixture.componentRef.setInput('orders', [exit({ orderId: 'sl', orderType: 'STOP', limitPrice: null, stopPrice: 90 })]);
      fixture.detectChanges();

      expect(component.rows()[0].linked).toBe(false);
      expect(fixture.nativeElement.querySelector('[data-testid="oco-badge"]')).toBeNull();
    });
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

  it('shows what a trailing stop trails and the best price it has followed', () => {
    fixture.componentRef.setInput('orders', [order({
      side: 'SELL', orderType: 'TRAILING_STOP', stopPrice: 146.5, limitPrice: null, reservedCash: null,
      trailPercent: 2, trailPeak: 149.5
    })]);
    fixture.detectChanges();
    expect(component.rows()[0].target).toBe(146.5);
    expect(text()).toContain('Trailing stop sell');
    expect(text()).toContain('Trails 2% from its high $149.50');
  });

  describe('editing', () => {
    const url = '/api/order/orders/o1';

    function open(): void {
      click('Edit');
    }

    it('opens a form with the order values, and closes it again', () => {
      open();
      expect(fixture.nativeElement.querySelector('[data-testid="edit-order"]')).not.toBeNull();
      expect(component.draft()).toEqual({ quantity: 2, limit: 95, stop: null, trail: null, tif: 'GTC' });
      click('Close');
      expect(fixture.nativeElement.querySelector('[data-testid="edit-order"]')).toBeNull();
    });

    it('sends only what changed, then reports it', () => {
      const changed: OpenOrder[] = [];
      component.changed.subscribe(o => changed.push(o));
      open();
      component.patch('quantity', 3);
      component.patch('limit', 90);
      component.save(order());

      const request = http.expectOne(url);
      expect(request.request.method).toBe('PATCH');
      expect(request.request.body).toEqual({ quantity: 3, limitPrice: 90 });
      request.flush(order({ quantity: 3, limitPrice: 90 }));
      fixture.detectChanges();
      expect(changed.length).toBe(1);
      expect(component.editing()).toBeNull();
    });

    it('sends nothing when nothing changed', () => {
      open();
      component.save(order());
      http.expectNone(url);
      expect(component.editing()).toBeNull();
    });

    it('wants a whole number of shares', () => {
      open();
      component.patch('quantity', 1.5);
      component.save(order());
      http.expectNone(url);
      expect(component.editError()).toBe('Enter a whole number of shares');
    });

    it('edits a trailing stop in the unit it was placed in, and not its stop price', () => {
      const trailing = order({
        side: 'SELL', orderType: 'TRAILING_STOP', stopPrice: 146.5, limitPrice: null, reservedCash: null, trailPercent: 2
      });
      fixture.componentRef.setInput('orders', [trailing]);
      fixture.detectChanges();
      open();
      expect(component.draft().stop).toBeNull();
      component.patch('trail', 3);
      component.patch('tif', 'DAY');
      component.save(trailing);
      expect(http.expectOne(url).request.body).toEqual({ trailPercent: 3, timeInForce: 'DAY' });
    });

    it('shows why a change was refused and keeps the form open', () => {
      open();
      component.patch('limit', 99);
      component.save(order());
      http.expectOne(url).flush({ message: 'Insufficient funds - order rejected' }, { status: 400, statusText: 'Bad Request' });
      fixture.detectChanges();
      expect(text()).toContain('Insufficient funds - order rejected');
      expect(component.editing()).toBe('o1');
    });

    it('reloads when the order had already filled', () => {
      const changed: OpenOrder[] = [];
      component.changed.subscribe(o => changed.push(o));
      open();
      component.patch('limit', 99);
      component.save(order());
      http.expectOne(url).flush({ message: 'This order has already executed and can no longer be changed' },
        { status: 409, statusText: 'Conflict' });
      fixture.detectChanges();
      expect(text()).toContain('already executed');
      expect(changed.length).toBe(1);
    });

    it('does not offer a reached stop-limit\'s stop price for editing', () => {
      const reached = order({ side: 'SELL', orderType: 'STOP_LIMIT', stopPrice: 101, limitPrice: 100,
        triggeredAt: '2026-10-12T12:00:00Z', reservedCash: null });
      expect(component.hasStop(reached)).toBe(false);
      expect(component.hasLimit(reached)).toBe(true);
    });
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
