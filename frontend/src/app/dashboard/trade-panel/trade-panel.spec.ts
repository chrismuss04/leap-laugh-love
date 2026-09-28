// Order placement confirmation: exercise the real template and services with intercepted HTTP requests.
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ClientProfile } from '../../services/profile';
import { OrderSide } from '../../services/order';
import { TradePanelComponent } from './trade-panel';

describe('TradePanelComponent order confirmation', () => {
  let fixture: ComponentFixture<TradePanelComponent>;
  let component: TradePanelComponent;
  let http: HttpTestingController;
  const profileUrl = '/api/iam/v1/clients/me';
  const ordersUrl = '/api/order/orders';

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [TradePanelComponent],
      providers: [provideHttpClient(), provideHttpClientTesting()]
    }).compileComponents();
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(TradePanelComponent);
    component = fixture.componentInstance;
    fixture.componentRef.setInput('symbol', 'AAPL');
    fixture.componentRef.setInput('name', 'Apple Inc.');
    fixture.componentRef.setInput('price', 100);
    fixture.componentRef.setInput('accounts', [{
      accountId: 'account-1', accountNumber: 'ACC-001',
      buyingPower: 100_000, shares: { AAPL: 1_000 }
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

  function button(selector: string): HTMLButtonElement {
    const element = fixture.nativeElement.querySelector(selector);
    if (!element) throw new Error(`Missing button: ${selector}`);
    return element;
  }

  function dialog(): HTMLDialogElement {
    return fixture.nativeElement.querySelector('dialog');
  }

  function review(quantity = 1, side: OrderSide = 'BUY'): void {
    component.quantity = quantity;
    component.side = side;
    component.review();
    fixture.detectChanges();
  }

  function submit(): void {
    button('.review .cta').click();
    fixture.detectChanges();
  }

  function completeOrder(side: OrderSide, quantity: number): void {
    const request = http.expectOne(ordersUrl);
    expect(request.request.method).toBe('POST');
    expect(request.request.body).toEqual({ accountId: 'account-1', symbol: 'AAPL', side, quantity });
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
            completeOrder(side, 1);
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
      expect(component.step).toBe('submitting');
      completeOrder(side, 3);
      expect(component.step).toBe('result');
    });
  }

  it('Go back closes the popup and requires confirmation again', () => {
    loadProfile('NOVICE');
    review();
    submit();
    button('.confirmation-actions .ghost').click();
    expect(dialog().open).toBe(false);
    expect(component.step).toBe('review');
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
    expect(component.confirmationOpen).toBe(false);
    http.expectNone(ordersUrl);
  });

  it('requires confirmation while the profile is still loading', () => {
    review();
    submit();
    expect(dialog().open).toBe(true);
    http.expectNone(ordersUrl);
    loadProfile('ADVANCED');
    expect(component.confirmationOpen).toBe(true);
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
    expect(component.step).toBe('review');
    expect(component.submitError).toContain('Not enough buying power');
    http.expectNone(ordersUrl);
  });

  it('editing discards confirmation and shows the updated quantity next time', () => {
    loadProfile('NOVICE');
    review();
    submit();
    button('.confirmation-actions .ghost').click();
    button('.review .ghost').click();
    expect(component.step).toBe('edit');
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
    expect(component.step).toBe('edit');
    component.confirmOrder();
    http.expectNone(ordersUrl);
  });
});
