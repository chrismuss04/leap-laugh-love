import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideZonelessChangeDetection } from '@angular/core';
import { provideRouter } from '@angular/router';
import { TradePanelComponent } from './trade-panel';
import { MarketStatusService } from '../../services/market-status';
import { MarketSession, MarketStatus } from '../../shared/market-session';
import { OrderType } from '../../services/order';

/**
 * The ticket outside regular hours: market orders queue for the open behind a 5% collar, limit
 * orders can opt in to extended hours, and stops say they wait for regular trading.
 */
describe('TradePanelComponent market hours', () => {
  let fixture: ComponentFixture<TradePanelComponent>;
  let component: TradePanelComponent;
  let http: HttpTestingController;
  let market: MarketStatusService;
  const ordersUrl = '/api/order/orders';

  function status(session: MarketSession): MarketStatus {
    return {
      session, now: new Date().toISOString(), nextSession: null, nextTransitionAt: null,
      nextRegularOpen: session === 'REGULAR' ? new Date().toISOString() : '2026-10-16T13:30:00Z',
      today: {
        date: '2026-10-15', open: true, holiday: null, earlyClose: false,
        preMarketOpen: null, regularOpen: null, regularClose: null, afterHoursClose: null
      },
      mode: 'REAL', forced: false
    };
  }

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [TradePanelComponent],
      providers: [provideHttpClient(), provideHttpClientTesting(), provideZonelessChangeDetection(), provideRouter([])]
    }).compileComponents();
    http = TestBed.inject(HttpTestingController);
    market = TestBed.inject(MarketStatusService);
    fixture = TestBed.createComponent(TradePanelComponent);
    component = fixture.componentInstance;
    fixture.componentRef.setInput('symbol', 'AAPL');
    fixture.componentRef.setInput('price', 100);
    fixture.componentRef.setInput('accounts', [{
      accountId: 'account-1', accountNumber: 'ACC-001',
      buyingPower: 100_000, shares: { AAPL: 1_000 }, maxSlippagePercent: null
    }]);
    fixture.detectChanges();
    // An experienced client trading under $25,000 isn't asked to confirm.
    http.expectOne('/api/iam/v1/clients/me').flush({
      clientId: 'client-1', email: 'client@example.com', fullName: 'Test Client',
      experienceLevel: 'ADVANCED', status: 'ACTIVE'
    });
  });

  afterEach(() => {
    http.match(req => req.url.includes('/history'));
    http.match(req => req.url.includes('/api/order/templates'));
    http.verify();
    fixture.destroy();
  });

  function session(value: MarketSession): void {
    market.status.set(status(value));
    fixture.detectChanges();
  }

  function el(selector: string): HTMLElement | null {
    return fixture.nativeElement.querySelector(selector);
  }

  function text(): string {
    return (fixture.nativeElement.textContent as string).replace(/\s+/g, ' ');
  }

  function ticket(type: OrderType, quantity: number, side: 'BUY' | 'SELL' = 'BUY'): void {
    component.setSide(side);
    component.setOrderType(type);
    component.quantity.set(quantity);
    fixture.detectChanges();
  }

  it('looks as before during regular hours', () => {
    session('REGULAR');
    ticket('MARKET', 5);

    expect(el('[data-testid="session-strip"]')).toBeNull();
    expect(el('[data-testid="session-tag"]')).toBeNull();
    expect(el('[data-testid="session-note"]')).toBeNull();
    expect(component.queued()).toBe(false);
    expect(text()).toContain('Price protection');
  });

  it('treats an unknown session as regular hours', () => {
    ticket('MARKET', 5);
    expect(component.queued()).toBe(false);
  });

  describe('a market order after hours', () => {
    beforeEach(() => {
      session('AFTER_HOURS');
      ticket('MARKET', 5);
    });

    it('shows the session and says the order will be queued for the open', () => {
      expect(el('[data-testid="session-tag"]')!.textContent!.trim()).toBe('After-hours');
      expect(el('[data-testid="session-strip"]')!.textContent).toContain('Next open');
      expect(el('[data-testid="session-strip"]')!.textContent).toContain('9:30 AM ET');
      expect(el('[data-testid="session-note"]')!.textContent).toContain('queued and placed at the open');
      expect(el('[data-testid="order-sentence"]')!.textContent).toContain('Buy 5 shares of AAPL at the market open');
    });

    it('swaps price protection for the 5% collar, and holds buying power at it', () => {
      expect(el('[data-testid="collar-row"]')!.textContent).toContain('Up to $105.00');
      expect(text()).not.toContain('Price protection');
      expect(component.costToHold()).toBe(525);
      expect(el('.ticket .cta')!.textContent!.trim()).toBe('Review queued order');
    });

    it('is blocked when buying power covers the price but not the collar', () => {
      fixture.componentRef.setInput('accounts', [{
        accountId: 'account-1', accountNumber: 'ACC-001', buyingPower: 1_000, shares: {}, maxSlippagePercent: null
      }]);
      component.quantity.set(10);
      fixture.detectChanges();

      // $1,000 buys 10 shares at $100.00, but the collar holds $1,050.
      expect(component.blocker()).toContain('Not enough buying power');
    });

    it('reviews and queues the order without a quote or price protection', () => {
      component.review();
      fixture.detectChanges();
      http.expectNone('/api/marketdata/quotes/AAPL');

      expect(el('[data-testid="review-queued"]')!.textContent).toContain('queuing a market order');
      expect(el('[data-testid="review-collar"]')!.textContent).toContain('Up to $105.00');
      expect(el('.review .cta')!.textContent!.trim()).toBe('Queue buy');

      (el('.review .cta') as HTMLButtonElement).click();
      fixture.detectChanges();
      const request = http.expectOne(ordersUrl);
      expect(request.request.body).toEqual({ accountId: 'account-1', symbol: 'AAPL', side: 'BUY', quantity: 5 });
      request.flush({
        orderId: 'order-1', accountId: 'account-1', accountNumber: 'ACC-001', symbol: 'AAPL', side: 'BUY',
        quantity: 5, status: 'OPEN', submittedAt: '2026-10-15T21:00:00Z', filledAt: null, rejectionReason: null,
        execution: null, accountBalanceAfter: null, orderType: 'MARKET', timeInForce: 'DAY',
        reservedCash: 525, collarPrice: 105
      });
      fixture.detectChanges();

      const result = el('[data-testid="trade-result"]')!.textContent!.replace(/\s+/g, ' ');
      expect(result).toContain('Queued for market open');
      expect(result).toContain('when the market opens');
      expect(result).toContain('Up to $105.00');
      expect(result).toContain('$525.00');
    });
  });

  it('gives a queued sell a collar 5% below the bid', () => {
    session('CLOSED');
    ticket('MARKET', 5, 'SELL');

    expect(el('[data-testid="session-tag"]')!.textContent!.trim()).toBe('Closed');
    expect(el('[data-testid="collar-row"]')!.textContent).toContain('At least $95.00');
  });

  describe('a limit order outside regular hours', () => {
    it('offers extended hours, on by default in pre-market, and sends it', async () => {
      session('PRE_MARKET');
      ticket('LIMIT', 5);
      component.setLimitPrice(99);
      fixture.detectChanges();
      // ngModel writes the switch's state asynchronously.
      await fixture.whenStable();

      const toggle = el('[data-testid="extended-hours"] input') as HTMLInputElement;
      expect(toggle.checked).toBe(true);
      expect(el('[data-testid="session-note"]')).toBeNull();
      expect(el('[data-testid="order-sentence"]')!.textContent).toContain('including extended hours');

      component.review();
      fixture.detectChanges();
      expect(el('[data-testid="review-extended"]')!.textContent).toContain('Regular and extended');
      (el('.review .cta') as HTMLButtonElement).click();
      const request = http.expectOne(ordersUrl);
      expect(request.request.body.extendedHours).toBe(true);
      request.flush({ status: 'OPEN', orderType: 'LIMIT', side: 'BUY', quantity: 5, symbol: 'AAPL' });
    });

    it('says a regular-hours limit waits for regular trading, and sends no extendedHours', () => {
      session('PRE_MARKET');
      ticket('LIMIT', 5);
      component.setLimitPrice(99);
      component.setExtendedHours(false);
      fixture.detectChanges();

      expect(el('[data-testid="session-note"]')!.textContent).toContain('Without extended hours');

      component.review();
      fixture.detectChanges();
      (el('.review .cta') as HTMLButtonElement).click();
      const request = http.expectOne(ordersUrl);
      expect(request.request.body.extendedHours).toBeUndefined();
      request.flush({ status: 'OPEN', orderType: 'LIMIT', side: 'BUY', quantity: 5, symbol: 'AAPL' });
    });

    it('only offers extended hours on a limit order', () => {
      session('AFTER_HOURS');
      ticket('STOP', 5, 'SELL');
      expect(el('[data-testid="extended-hours"]')).toBeNull();
      expect(el('[data-testid="session-note"]')!.textContent).toContain('Stops only trigger during regular market hours');
    });
  });

  it('starts each new ticket with the session default for extended hours', () => {
    session('AFTER_HOURS');
    ticket('LIMIT', 5);
    component.setExtendedHours(false);
    expect(component.extendedHours()).toBe(false);

    component.reset();
    expect(component.extendedHours()).toBe(true);
  });
});
