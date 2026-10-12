// Order placement confirmation: exercise the real template and services with intercepted HTTP requests.
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideZonelessChangeDetection } from '@angular/core';
import { provideRouter } from '@angular/router';
import { ClientProfile } from '../../services/profile';
import { OrderSide } from '../../services/order';
import { TradePanelComponent } from './trade-panel';

describe('TradePanelComponent order confirmation', () => {
  let fixture: ComponentFixture<TradePanelComponent>;
  let component: TradePanelComponent;
  let http: HttpTestingController;
  const profileUrl = '/api/iam/v1/clients/me';
  const ordersUrl = '/api/order/orders';
  const quoteUrl = '/api/marketdata/quotes/AAPL';

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [TradePanelComponent],
      providers: [provideHttpClient(), provideHttpClientTesting(), provideZonelessChangeDetection(), provideRouter([])]
    }).compileComponents();
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(TradePanelComponent);
    component = fixture.componentInstance;
    fixture.componentRef.setInput('symbol', 'AAPL');
    fixture.componentRef.setInput('name', 'Apple Inc.');
    fixture.componentRef.setInput('price', 100);
    fixture.componentRef.setInput('accounts', [{
      accountId: 'account-1', accountNumber: 'ACC-001',
      buyingPower: 100_000, shares: { AAPL: 1_000 }, maxSlippagePercent: null
    }]);
    fixture.detectChanges();
  });

  afterEach(() => {
    http.verify();
    fixture.destroy();
  });

  function loadProfile(experienceLevel: ClientProfile['experienceLevel']): void {
    http.expectOne(profileUrl).flush({
      clientId: 'client-1', email: 'client@example.com', fullName: 'Test Client',
      experienceLevel, status: 'ACTIVE'
    });
  }

  // Regression: opening from the dashboard timer must schedule a template refresh.
  it('renders Sell after an asynchronous open without a manual change-detection pass', async () => {
    loadProfile('ADVANCED');
    await fixture.whenStable();
    await new Promise<void>(resolve => setTimeout(() => {
      component.open('SELL');
      resolve();
    }));
    await fixture.whenStable();
    expect(fixture.nativeElement.querySelector('section.panel').getAttribute('data-side')).toBe('SELL');
  });

  function button(selector: string): HTMLButtonElement {
    const element = fixture.nativeElement.querySelector(selector);
    if (!element) throw new Error(`Missing button: ${selector}`);
    return element;
  }

  function dialog(): HTMLDialogElement {
    return fixture.nativeElement.querySelector('dialog');
  }

  /** Answers the quote reviewing asks for; by default with no spread, at the streamed price. */
  function flushQuote(ask = component.price()!, bid = ask): void {
    http.expectOne(quoteUrl).flush({
      symbol: 'AAPL', bidPrice: bid, askPrice: ask, lastPrice: (ask + bid) / 2,
      quoteTimestamp: '2026-09-28T12:00:00Z'
    });
    fixture.detectChanges();
  }

  function review(quantity = 1, side: OrderSide = 'BUY'): void {
    component.quantity.set(quantity);
    component.side.set(side);
    component.review();
    fixture.detectChanges();
    flushQuote();
  }

  function submit(): void {
    button('.review .cta').click();
    fixture.detectChanges();
  }

  function completeOrder(side: OrderSide, quantity: number, quotedPrice = 100): void {
    const request = http.expectOne(ordersUrl);
    expect(request.request.method).toBe('POST');
    expect(request.request.body).toEqual({ accountId: 'account-1', symbol: 'AAPL', side, quantity, quotedPrice });
    request.flush({
      orderId: 'order-1', accountId: 'account-1', accountNumber: 'ACC-001',
      symbol: 'AAPL', side, quantity, status: 'FILLED',
      submittedAt: '2026-09-28T12:00:00Z', filledAt: '2026-09-28T12:00:00Z',
      rejectionReason: null, execution: null, accountBalanceAfter: null
    });
  }

  for (const side of ['BUY', 'SELL'] as const) {
    it(`prompts a novice only after Submit ${side}, without sending an order`, () => {
      loadProfile('NOVICE');
      review(1, side);
      expect(dialog().open).toBe(false);
      submit();
      expect(dialog().open).toBe(true);
      expect(dialog().textContent).toContain('AAPL');
      expect(dialog().textContent).toContain('ACC-001');
      expect(dialog().textContent).toContain('Apple Inc.');
      expect(dialog().textContent).toContain(component.money(100));
      http.expectNone(ordersUrl);
    });

    for (const level of ['INTERMEDIATE', 'ADVANCED'] as const) {
      for (const total of [24_999.99, 25_000, 25_000.01]) {
        it(`${level} ${side}: ${total >= 25_000 ? 'confirms' : 'directly submits'} $${total}`, () => {
          loadProfile(level);
          fixture.componentRef.setInput('price', total);
          review(1, side);
          submit();
          expect(dialog().open).toBe(total >= 25_000);
          if (total >= 25_000) {
            http.expectNone(ordersUrl);
          } else {
            completeOrder(side, 1, total);
          }
        });
      }
    }

    it(`submits exactly one ${side} order after confirmation`, () => {
      loadProfile('NOVICE');
      review(3, side);
      submit();
      button('.confirmation-actions .cta').click();
      component.confirmOrder();
      component.submit();
      expect(dialog().open).toBe(false);
      expect(component.step()).toBe('submitting');
      completeOrder(side, 3);
      expect(component.step()).toBe('result');
    });
  }

  // Regression: the app is zoneless, so an HTTP callback only refreshes the view through signals.
  // E2E saw NG0100 when a later, unrelated refresh found the error the panel never rendered.
  describe('renders the submission outcome without a manual change-detection pass', () => {
    beforeEach(() => {
      loadProfile('ADVANCED');
      review(2);
      submit();
    });

    it('shows a refusal and keeps the review open', async () => {
      http.expectOne(ordersUrl).flush(
        { error: 'STALE_QUOTE', message: 'Quote for AAPL is stale; please retry' },
        { status: 409, statusText: 'Conflict' });
      await fixture.whenStable();
      expect(fixture.nativeElement.querySelector('.review .error')?.textContent).toContain('Quote for AAPL is stale; please retry');
      expect(button('.review .cta').disabled).toBe(false);
    });

    it('shows a fill', async () => {
      completeOrder('BUY', 2);
      await fixture.whenStable();
      expect(fixture.nativeElement.querySelector('[data-testid="trade-result"]')?.textContent).toContain('Bought 2');
    });

    it('warns when the outcome is unknown', async () => {
      http.expectOne(ordersUrl).flush({ message: 'boom' }, { status: 500, statusText: 'Server Error' });
      await fixture.whenStable();
      expect(fixture.nativeElement.querySelector('[data-testid="trade-unconfirmed"]')?.textContent)
        .toContain("We couldn't confirm your order");
    });
  });

  it('Go back closes the popup and requires confirmation again', () => {
    loadProfile('NOVICE');
    review();
    submit();
    button('.confirmation-actions .ghost').click();
    expect(dialog().open).toBe(false);
    expect(component.step()).toBe('review');
    component.confirmOrder();
    http.expectNone(ordersUrl);
    submit();
    expect(dialog().open).toBe(true);
  });

  it('handles the native Escape cancel event without submitting', () => {
    loadProfile('NOVICE');
    review();
    submit();
    dialog().dispatchEvent(new Event('cancel', { cancelable: true }));
    expect(dialog().open).toBe(false);
    expect(component.confirmationOpen()).toBe(false);
    http.expectNone(ordersUrl);
  });

  it('requires confirmation while the profile is still loading', () => {
    review();
    submit();
    expect(dialog().open).toBe(true);
    http.expectNone(ordersUrl);
    loadProfile('ADVANCED');
    expect(component.confirmationOpen()).toBe(true);
  });

  it('requires confirmation if the profile request fails', () => {
    http.expectOne(profileUrl).flush('Unavailable', { status: 503, statusText: 'Unavailable' });
    review();
    submit();
    expect(dialog().open).toBe(true);
    http.expectNone(ordersUrl);
  });

  it('checks the latest price when Submit is clicked', () => {
    loadProfile('ADVANCED');
    review(249);
    fixture.componentRef.setInput('price', 101);
    fixture.detectChanges();
    submit();
    expect(dialog().open).toBe(true);
    expect(dialog().textContent).toContain(component.money(25_149));
    http.expectNone(ordersUrl);
  });

  it('does not submit if a price change makes the order unaffordable', () => {
    loadProfile('NOVICE');
    review(250);
    submit();
    fixture.componentRef.setInput('price', 500);
    fixture.detectChanges();
    expect(button('.confirmation-actions .cta').disabled).toBe(true);
    component.confirmOrder();
    expect(component.step()).toBe('review');
    expect(component.submitError()).toContain('Not enough buying power');
    http.expectNone(ordersUrl);
  });

  it('editing discards confirmation and shows the updated quantity next time', () => {
    loadProfile('NOVICE');
    review();
    submit();
    button('.confirmation-actions .ghost').click();
    button('.review .ghost').click();
    expect(component.step()).toBe('edit');
    review(2);
    submit();
    expect(dialog().open).toBe(true);
    expect(dialog().textContent).toContain(component.money(200));
    http.expectNone(ordersUrl);
  });

  it('changing stocks closes an existing prompt without submitting', () => {
    loadProfile('NOVICE');
    review();
    submit();
    fixture.componentRef.setInput('symbol', 'MSFT');
    fixture.detectChanges();
    expect(dialog().open).toBe(false);
    expect(component.step()).toBe('edit');
    component.confirmOrder();
    http.expectNone(ordersUrl);
  });

  describe('price protection', () => {
    function withSavedProtection(value: number | null): void {
      fixture.componentRef.setInput('accounts', [{
        accountId: 'account-1', accountNumber: 'ACC-001',
        buyingPower: 100_000, shares: { AAPL: 1_000 }, maxSlippagePercent: value
      }]);
      fixture.detectChanges();
    }

    function chips(): HTMLButtonElement[] {
      return Array.from(fixture.nativeElement.querySelectorAll('app-protection-picker .chip'));
    }

    function openPicker(): void {
      button('.protection-toggle').click();
      fixture.detectChanges();
    }

    function chip(label: string): HTMLButtonElement {
      const found = chips().find(c => c.textContent?.trim() === label);
      if (!found) throw new Error(`Missing chip: ${label}`);
      return found;
    }

    function startReview(side: OrderSide = 'BUY'): void {
      component.quantity.set(2);
      component.side.set(side);
      component.review();
      fixture.detectChanges();
    }

    function sentOrder(): Record<string, unknown> {
      submit();
      const request = http.expectOne(ordersUrl);
      const body = request.request.body;
      request.flush({
        orderId: 'order-1', accountId: 'account-1', accountNumber: 'ACC-001', symbol: 'AAPL', side: body.side,
        quantity: body.quantity, status: 'FILLED', submittedAt: '2026-09-28T12:00:00Z',
        filledAt: '2026-09-28T12:00:00Z', rejectionReason: null, execution: null, accountBalanceAfter: null
      });
      return body;
    }

    function reject(reason: string): void {
      submit();
      http.expectOne(ordersUrl).flush({
        orderId: 'order-1', accountId: 'account-1', accountNumber: 'ACC-001', symbol: 'AAPL', side: 'BUY',
        quantity: 2, status: 'REJECTED', submittedAt: '2026-09-28T12:00:00Z', filledAt: null,
        rejectionReason: reason, execution: null, accountBalanceAfter: null
      });
      fixture.detectChanges();
    }

    beforeEach(() => loadProfile('ADVANCED'));

    for (const [side, expected] of [['BUY', 100.05], ['SELL', 99.95]] as const) {
      it(`quotes a ${side} at the ${side === 'BUY' ? 'ask' : 'bid'} it will fill at, and sends that quote`, () => {
        startReview(side);
        flushQuote(100.05, 99.95);
        expect(component.quotedPrice()).toBe(expected);
        expect(sentOrder()).toEqual({ accountId: 'account-1', symbol: 'AAPL', side, quantity: 2, quotedPrice: expected });
      });
    }

    it('keeps the streamed price as the quote when the quote request fails', () => {
      startReview();
      http.expectOne(quoteUrl).flush('Unavailable', { status: 503, statusText: 'Unavailable' });
      expect(component.quotedPrice()).toBe(100);
      expect(sentOrder()['quotedPrice']).toBe(100);
    });

    it('is Off with no saved protection, and offers Off among the choices', () => {
      expect(button('.protection-toggle').textContent).toContain('Off');
      openPicker();
      expect(chips().map(c => c.textContent?.trim())).toEqual(['Off', '0.25%', '0.5%', '1%', '2%']);
    });

    it("starts from the account's saved protection, shows its range on review and sends it", () => {
      withSavedProtection(0.5);
      expect(button('.protection-toggle').textContent).toContain('±0.5%');
      startReview();
      flushQuote(100);
      expect(fixture.nativeElement.querySelector('[data-testid="review-protection"]').textContent)
        .toContain('±0.5% · $99.50–$100.50');
      expect(sentOrder()['maxSlippagePercent']).toBe(0.5);
    });

    it('lets one order use a different protection without changing the saved one', () => {
      withSavedProtection(0.5);
      openPicker();
      // Off can't override a saved protection for a single order.
      expect(chips().some(c => c.textContent?.trim() === 'Off')).toBe(false);
      chip('1%').click();
      fixture.detectChanges();
      expect(component.protection()).toBe(1);
      expect(chip('1%').getAttribute('aria-checked')).toBe('true');

      startReview();
      flushQuote();
      expect(sentOrder()['maxSlippagePercent']).toBe(1);
      component.reset();
      expect(component.protection()).toBe(0.5);
    });

    it('shows a saved protection that is not a preset as its own choice', () => {
      withSavedProtection(0.75);
      openPicker();
      expect(chip('0.75%').getAttribute('aria-checked')).toBe('true');
    });

    it('explains a price-move rejection and reviews the same order again at the new price', () => {
      withSavedProtection(0.5);
      startReview();
      flushQuote(100);
      reject('Price moved 1.20% from your quoted $100.00 to $101.20, beyond your 0.50% tolerance - order rejected');
      const result: HTMLElement = fixture.nativeElement.querySelector('[data-testid="trade-result"]');
      expect(result.textContent).toContain('The price moved, so your order wasn\u2019t filled');

      fixture.componentRef.setInput('price', 101.2);
      button('[data-testid="trade-result"] .cta').click();
      fixture.detectChanges();
      expect(component.step()).toBe('review');
      expect(component.quantity()).toBe(2);
      flushQuote(101.25);
      expect(component.quotedPrice()).toBe(101.25);
    });

    it('offers no retry for other rejections', () => {
      startReview();
      flushQuote();
      reject('Insufficient funds - order rejected');
      const result: HTMLElement = fixture.nativeElement.querySelector('[data-testid="trade-result"]');
      expect(result.textContent).toContain('Order not filled');
      expect(result.textContent).not.toContain('Review at new price');
    });
  });
});
