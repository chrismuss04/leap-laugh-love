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
    // Setting up a limit, stop or trailing order asks for a month of daily candles for the hit-rate hint.
    http.match(req => req.url.includes('/history'));
    // The ticket loads the client's saved templates when it opens.
    http.match(req => req.url.includes('/api/order/templates'));
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

  describe('order types', () => {
    beforeEach(() => loadProfile('ADVANCED'));

    function typeButton(label: string): HTMLLabelElement {
      const found = Array.from<HTMLLabelElement>(fixture.nativeElement.querySelectorAll('.types .type'))
        .find(b => b.textContent?.trim() === label);
      if (!found) throw new Error(`Missing order type: ${label}`);
      return found;
    }

    function pick(label: string): void {
      typeButton(label).click();
      fixture.detectChanges();
    }

    function text(selector: string): string {
      return fixture.nativeElement.querySelector(selector)?.textContent?.replace(/\s+/g, ' ').trim() ?? '';
    }

    function place() {
      component.review();
      fixture.detectChanges();
      submit();
      return http.expectOne(ordersUrl);
    }

    it('offers market, limit, stop, stop limit and trailing stop, starting on market', () => {
      const labels = Array.from<HTMLElement>(fixture.nativeElement.querySelectorAll('.types .type'))
        .map(b => b.textContent?.trim());
      expect(labels).toEqual(['Market', 'Limit', 'Stop', 'Stop limit', 'Trailing stop']);
      expect(typeButton('Market').querySelector('input')!.checked).toBe(true);
      expect(typeButton('Limit').querySelector('input')!.checked).toBe(false);
      expect(fixture.nativeElement.querySelector('#trade-limit')).toBeNull();
    });

    it('suggests a limit 1% better than the market, and sends a limit order without price protection', () => {
      pick('Limit');
      expect(component.limitPrice()).toBe(99);
      expect(text('.price-field .distance')).toBe('1.0% below the current price');
      component.quantity.set(3);
      component.setTimeInForce('GTC');
      fixture.detectChanges();
      expect(text('[data-testid="order-sentence"]')).toBe('Buy 3 shares of AAPL at $99.00 or lower, good until cancelled.');

      expect(place().request.body).toEqual({
        accountId: 'account-1', symbol: 'AAPL', side: 'BUY', quantity: 3,
        orderType: 'LIMIT', limitPrice: 99, timeInForce: 'GTC'
      });
    });

    it('suggests a sell stop 5% below the market, with a break-even chip above cost', () => {
      fixture.componentRef.setInput('accounts', [{
        accountId: 'account-1', accountNumber: 'ACC-001', buyingPower: 100_000, shares: { AAPL: 10 },
        maxSlippagePercent: null, averageCost: { AAPL: 80 }
      }]);
      component.setSide('SELL');
      pick('Stop');
      expect(component.stopPrice()).toBe(95);
      expect(component.stopChips().map(c => c.label)).toEqual(['−2%', '−5%', '−10%', 'Break-even']);
      component.setStopPrice(component.stopChips()[3].value);
      component.quantity.set(10);
      fixture.detectChanges();
      expect(component.stopPrice()).toBe(80);
      expect(component.blocker()).toBeNull();
    });

    it('blocks a sell stop at or above the market, which would trigger at once', () => {
      component.setSide('SELL');
      pick('Stop');
      component.quantity.set(1);
      component.setStopPrice(101);
      fixture.detectChanges();
      expect(component.blocker()).toBe('A sell stop must be below the current price ($100.00)');
      expect(component.canReview()).toBe(false);
    });

    it('needs a price for the type before review', () => {
      pick('Stop limit');
      component.quantity.set(1);
      component.setLimitPrice(null);
      fixture.detectChanges();
      expect(component.blocker()).toBe('Enter a limit price');
    });

    it('warns of a limit that would fill at once, and one that may never be reached', () => {
      pick('Limit');
      component.setLimitPrice(101);
      expect(component.warning()).toContain('will fill right away');
      component.setLimitPrice(60);
      expect(component.warning()).toContain('40% from the current price');
    });

    it('checks a buy against what it will hold, not the market estimate', () => {
      fixture.componentRef.setInput('accounts', [{
        accountId: 'account-1', accountNumber: 'ACC-001', buyingPower: 1_000, shares: {}, maxSlippagePercent: null
      }]);
      pick('Stop');
      component.quantity.set(10);
      component.setStopPrice(98);
      fixture.detectChanges();
      // A stop buy holds 5% over its stop, as it can fill above it.
      expect(component.blocker()).toBe('A buy stop must be above the current price ($100.00)');
      component.setStopPrice(99.5);
      expect(component.costToHold()).toBeCloseTo(1044.75);
      component.setStopPrice(101);
      expect(component.blocker()).toBe('Not enough buying power ($1,000.00 available)');
    });

    it('counts shares open orders hold as unavailable to sell', () => {
      fixture.componentRef.setInput('accounts', [{
        accountId: 'account-1', accountNumber: 'ACC-001', buyingPower: 100_000, shares: { AAPL: 10 },
        maxSlippagePercent: null, sharesOnHold: { AAPL: 8 }
      }]);
      component.setSide('SELL');
      component.quantity.set(5);
      fixture.detectChanges();
      expect(component.sharesHeld()).toBe(2);
      expect(component.blocker()).toBe('Only 2 shares are free to sell — 8 are held for open orders');
    });

    describe('trailing stops', () => {
      beforeEach(() => {
        component.setSide('SELL');
        pick('Trailing stop');
        component.quantity.set(2);
        fixture.detectChanges();
      });

      it('starts at a 5% trail and shows where the stop starts', () => {
        expect(component.trailValue()).toBe(5);
        expect(component.initialStop()).toBe(95);
        expect(text('.price-field .distance')).toBe('Stop starts at $95.00');
        expect(text('[data-testid="order-sentence"]')).toBe(
          'If AAPL falls 5% from its high, sell 2 shares at the market price, good for today.');
        expect(component.blocker()).toBeNull();
      });

      it('sends the trail as a percent, with no stop price', () => {
        component.setTrailValue(2.5);
        expect(place().request.body).toEqual({
          accountId: 'account-1', symbol: 'AAPL', side: 'SELL', quantity: 2,
          orderType: 'TRAILING_STOP', timeInForce: 'DAY', trailPercent: 2.5
        });
      });

      it('switches to dollars keeping the same trail, and sends it as an amount', () => {
        component.setTrailUnit('AMOUNT');
        expect(component.trailValue()).toBe(5);
        component.setTrailValue(3);
        fixture.detectChanges();
        expect(text('[data-testid="order-sentence"]')).toContain('falls $3.00 from its high');
        expect(place().request.body).toEqual({
          accountId: 'account-1', symbol: 'AAPL', side: 'SELL', quantity: 2,
          orderType: 'TRAILING_STOP', timeInForce: 'DAY', trailAmount: 3
        });
      });

      it('trails a buy above its low', () => {
        component.setSide('BUY');
        fixture.detectChanges();
        expect(component.initialStop()).toBe(105);
        expect(text('[data-testid="order-sentence"]')).toContain('If AAPL rises 5% from its low, buy 2 shares');
      });

      it('checks the trail before review', () => {
        component.setTrailValue(60);
        expect(component.blocker()).toBe('Trail by between 0.1% and 50%');
        component.setTrailUnit('AMOUNT');
        component.setTrailValue(150);
        expect(component.blocker()).toBe('The trail must be smaller than the price');
        component.setTrailValue(null);
        expect(component.blocker()).toBe('Enter how far to trail');
      });

      it('warns that a very tight trail can trigger on ordinary moves', () => {
        component.setTrailValue(0.2);
        expect(component.warning()).toBe('A very tight trail can trigger on ordinary price moves.');
        component.setTrailValue(3);
        expect(component.warning()).toBeNull();
      });

      it('holds a buy for its starting stop plus the stop-buy buffer', () => {
        component.setSide('BUY');
        component.setTrailValue(5);
        // $105.00 stop x 1.05 x 2 shares
        expect(component.costToHold()).toBeCloseTo(220.5);
      });

      it('sets the trail by dragging the stop line on the chart', () => {
        component.onChartLine({ id: 'trail', value: 92 });
        expect(component.trailValue()).toBe(8);
        component.setTrailUnit('AMOUNT');
        component.onChartLine({ id: 'trail', value: 96.25 });
        expect(component.trailValue()).toBe(3.75);
      });

      it('explains an order left open', () => {
        component.setTrailValue(2);
        place().flush({
          orderId: 'order-1', accountId: 'account-1', accountNumber: 'ACC-001', symbol: 'AAPL', side: 'SELL',
          quantity: 2, status: 'OPEN', submittedAt: '2026-09-28T12:00:00Z', filledAt: null, rejectionReason: null,
          execution: null, accountBalanceAfter: null, orderType: 'TRAILING_STOP', limitPrice: null, stopPrice: 98,
          timeInForce: 'DAY', expiresAt: null, reservedCash: null, trailAmount: null, trailPercent: 2
        });
        fixture.detectChanges();
        expect(text('[data-testid="trade-open"]')).toContain('once AAPL falls 2% from its high');
      });
    });

    describe('price chart', () => {
      beforeEach(() => {
        fixture.componentRef.setInput('priceHistory', [98, 99, 101, 100]);
        fixture.detectChanges();
      });

      it('appears for orders that wait for a price, not for market orders', () => {
        expect(fixture.nativeElement.querySelector('app-order-chart')).toBeNull();
        pick('Limit');
        expect(fixture.nativeElement.querySelector('app-order-chart')).not.toBeNull();
      });

      it('draws the price line and a draggable line for each price entered', () => {
        pick('Stop limit');
        component.setSide('SELL');
        fixture.detectChanges();
        expect(component.chartLines().map(line => [line.id, line.draggable])).toEqual([
          ['market', false], ['stop', true], ['limit', true]
        ]);
      });

      it('sets a limit or stop price from its dragged line', () => {
        pick('Stop limit');
        component.onChartLine({ id: 'limit', value: 98.5 });
        component.onChartLine({ id: 'stop', value: 103.256 });
        expect(component.limitPrice()).toBe(98.5);
        expect(component.stopPrice()).toBe(103.26);
        component.onChartLine({ id: 'limit', value: -1 });
        expect(component.limitPrice()).toBe(98.5);
      });
    });

    describe('with a quote', () => {
      // Last trade $100.00; a buy fills at the ask, $100.05, a sell at the bid, $99.95.
      beforeEach(() => {
        fixture.componentRef.setInput('quoteOffset', { ask: 0.05, bid: -0.05 });
        fixture.detectChanges();
      });

      it('shows the bid and ask, highlighting the side the order fills at', () => {
        expect(text('[data-testid="bid-ask"]')).toBe('Bid $99.95 · Ask $100.05');
        expect(text('[data-testid="bid-ask"] .active')).toBe('Ask $100.05');
        component.setSide('SELL');
        fixture.detectChanges();
        expect(text('[data-testid="bid-ask"] .active')).toBe('Bid $99.95');
      });

      it('prices a market buy at the ask', () => {
        component.quantity.set(10);
        expect(component.estimate()).toBeCloseTo(1000.5);
      });

      it('measures a buy limit from the ask, offering the ask itself', () => {
        pick('Limit');
        component.setLimitPrice(100);
        fixture.detectChanges();
        expect(text('.price-field .distance')).toBe('0.05% below the ask');
        expect(component.limitChips()[0]).toEqual({ label: 'Ask', value: 100.05 });
        expect(component.warning()).toBeNull();
        component.setLimitPrice(100.05);
        expect(component.warning()).toBe('Your limit is at or above the ask, so this will fill right away.');
      });

      it('checks a sell stop against the bid', () => {
        component.setSide('SELL');
        pick('Stop');
        component.quantity.set(1);
        // Below the last trade, but not below the bid: it would trigger at once.
        component.setStopPrice(99.97);
        expect(component.blocker()).toBe('A sell stop must be below the bid ($99.95)');
        component.setStopPrice(99.9);
        expect(component.blocker()).toBeNull();
        expect(component.stopDistance()).toBe('0.05% below the bid');
      });
    });

    it('keeps the order type for the next ticket, with fresh prices', () => {
      pick('Limit');
      component.setLimitPrice(90);
      component.open('SELL');
      fixture.detectChanges();
      expect(component.orderType()).toBe('LIMIT');
      expect(component.limitPrice()).toBe(101);
    });

    it('explains an order left open, waiting for its price', () => {
      pick('Limit');
      component.quantity.set(2);
      place().flush({
        orderId: 'order-1', accountId: 'account-1', accountNumber: 'ACC-001', symbol: 'AAPL', side: 'BUY',
        quantity: 2, status: 'OPEN', submittedAt: '2026-09-28T12:00:00Z', filledAt: null, rejectionReason: null,
        execution: null, accountBalanceAfter: null, orderType: 'LIMIT', limitPrice: 99, stopPrice: null,
        timeInForce: 'DAY', expiresAt: '2026-09-29T04:00:00Z', reservedCash: 198
      });
      fixture.detectChanges();
      expect(text('[data-testid="trade-result"] .result-title')).toBe('Limit order placed');
      expect(text('[data-testid="trade-open"]')).toContain("We'll buy 2 shares of AAPL once AAPL falls to $99.00. It's good for today");
      expect(text('[data-testid="trade-result"] .summary')).toContain('$198.00');
    });

    describe('order impact', () => {
      beforeEach(() => {
        fixture.componentRef.setInput('accounts', [{
          accountId: 'account-1', accountNumber: 'ACC-001', buyingPower: 5_000, shares: { AAPL: 10 },
          maxSlippagePercent: null, averageCost: { AAPL: 80 }, totalValue: 10_000
        }]);
        fixture.detectChanges();
      });

      it('shows a buy\'s new position, share of the account, cash left and average cost', () => {
        component.quantity.set(10);
        fixture.detectChanges();
        const impact = component.impact()!;
        expect(impact.sharesAfter).toBe(20);
        expect(impact.cashAfter).toBe(4_000);
        expect(impact.averageCostAfter).toBe(90);
        expect(text('[data-testid="order-impact"]')).toContain('New position');
      });

      it('prices an open order at its own limit, not the market', () => {
        pick('Limit');
        component.setLimitPrice(90);
        component.quantity.set(10);
        fixture.detectChanges();
        expect(component.impact()!.cashAfter).toBe(4_100);
      });

      it('warns when the buy would make the stock too large a share of the account', () => {
        component.quantity.set(35);
        fixture.detectChanges();
        expect(text('[data-testid="concentration-warning"]')).toBe('AAPL would be 45% of your portfolio.');
      });

      it('shows a sell\'s realized gain and shares left', () => {
        component.setSide('SELL');
        component.quantity.set(4);
        fixture.detectChanges();
        const impact = component.impact()!;
        expect(impact.realized).toBe(80);
        expect(impact.sharesAfter).toBe(6);
        expect(text('[data-testid="order-impact"]')).toContain('Realized gain');
      });

      it('shows nothing without a valid quantity', () => {
        expect(component.impact()).toBeNull();
      });
    });

    describe('hit-rate hint', () => {
      const days = (n: number, low: number, high: number) => Array.from({ length: n }, () => ({
        bucketStart: '2026-09-01T00:00:00Z', bucketSeconds: 86400, open: low, high, low, close: high
      }));

      function flushHistory(candles: unknown[]): void {
        const requests = http.match(req => req.url.includes('/prices/AAPL/history'));
        expect(requests.length).toBe(1);
        requests[0].flush({ content: [...candles].reverse(), last: true });
        fixture.detectChanges();
      }

      it('is not asked for while the order is a market order', () => {
        expect(http.match(req => req.url.includes('/history')).length).toBe(0);
      });

      it('says how often the limit price was reached, as simulated history', () => {
        pick('Limit');
        fixture.detectChanges();
        flushHistory([...days(21, 101, 105), ...days(9, 95, 105)]);
        component.setLimitPrice(99);
        fixture.detectChanges();
        expect(text('[data-testid="hit-rate"]')).toContain('AAPL traded at or below $99.00 on 9 of the last 30 days.');
        expect(text('[data-testid="hit-rate"]')).toContain('not a prediction');
      });

      it('counts only the days available when there is less than a month', () => {
        pick('Limit');
        fixture.detectChanges();
        flushHistory(days(5, 95, 105));
        component.setLimitPrice(99);
        fixture.detectChanges();
        expect(text('[data-testid="hit-rate"]')).toContain('on 5 of the 5 days available');
      });

      it('shows no hint when history cannot be loaded', () => {
        pick('Limit');
        fixture.detectChanges();
        http.match(req => req.url.includes('/history'))[0].flush('boom', { status: 500, statusText: 'Server Error' });
        fixture.detectChanges();
        expect(component.hitRate()).toBeNull();
      });
    });

    describe('bracket ("Protect this trade")', () => {
      function toggle(): void {
        const box = fixture.nativeElement.querySelector('input[name="bracketOn"]') as HTMLInputElement;
        box.click();
        fixture.detectChanges();
      }

      it('is offered on a buy but not on a sell', () => {
        expect(fixture.nativeElement.querySelector('[data-testid="bracket"]')).not.toBeNull();
        component.setSide('SELL');
        fixture.detectChanges();
        expect(fixture.nativeElement.querySelector('[data-testid="bracket"]')).toBeNull();
      });

      it('starts at +10% and −5% of the price the buy enters at', () => {
        toggle();
        expect(component.takeProfit()).toBe(110);
        expect(component.stopLoss()).toBe(95);
        // Chips follow the entry price: a limit buy enters at its limit.
        pick('Limit');
        component.setLimitPrice(90);
        expect(component.takeProfitChips().map(c => c.value)).toEqual([94.5, 99, 108]);
        expect(component.stopLossChips().map(c => c.value)).toEqual([87.3, 85.5, 81]);
      });

      it('shows risk, reward and the ratio, and explains it in the sentence', () => {
        toggle();
        component.quantity.set(10);
        component.setTakeProfit(130);
        component.setStopLoss(88);
        fixture.detectChanges();
        expect(text('[data-testid="risk-reward"]')).toBe('Risk $120.00 · Reward $300.00 · 1 : 2.5');
        expect(text('[data-testid="order-sentence"]')).toContain('sell to take profit at $130.00 or to cut losses at $88.00');
      });

      it('draws both exits on the chart, as draggable lines', () => {
        toggle();
        const ids = component.chartLines().map(line => line.id);
        expect(ids).toContain('takeProfit');
        expect(ids).toContain('stopLoss');
        component.onChartLine({ id: 'takeProfit', value: 125.004 });
        component.onChartLine({ id: 'stopLoss', value: 91 });
        expect(component.takeProfit()).toBe(125);
        expect(component.stopLoss()).toBe(91);
      });

      it('blocks review when the exits are on the wrong side of the entry', () => {
        toggle();
        component.quantity.set(1);
        component.setTakeProfit(99);
        expect(component.blocker()).toBe('The take-profit must be above the price you buy at');
        component.setTakeProfit(120);
        component.setStopLoss(100);
        expect(component.blocker()).toBe('The stop-loss must be below the price you buy at');
        component.setStopLoss(90);
        expect(component.blocker()).toBeNull();
      });

      it('sends the bracket with the order, and shows the worst case in the impact preview', () => {
        pick('Limit');
        toggle();
        component.quantity.set(2);
        component.setLimitPrice(99);
        component.setTakeProfit(120);
        component.setStopLoss(90);
        fixture.detectChanges();
        expect(component.impact()!.worstCaseLoss).toBe(18);

        const request = place();
        expect(request.request.body).toEqual(expect.objectContaining({
          orderType: 'LIMIT', limitPrice: 99, bracketTakeProfitPrice: 120, bracketStopLossPrice: 90
        }));
        request.flush({}, { status: 500, statusText: 'x' });
      });

      it('sends nothing bracket-related when it is off, or after switching to a sell', () => {
        pick('Limit');
        toggle();
        component.setSide('SELL');
        component.quantity.set(1);
        component.setLimitPrice(101);
        const request = place();
        expect(request.request.body.bracketTakeProfitPrice).toBeUndefined();
        expect(request.request.body.bracketStopLossPrice).toBeUndefined();
        request.flush({}, { status: 500, statusText: 'x' });
      });
    });

    describe('templates', () => {
      const templatesUrl = '/api/order/templates';
      const swing = {
        templateId: 't1', name: 'Swing', side: 'BUY', orderType: 'MARKET', timeInForce: 'GTC',
        limitOffsetPercent: null, stopOffsetPercent: null, trailPercent: null,
        takeProfitPercent: 20, stopLossPercent: 8, createdAt: '2026-10-12T12:00:00Z'
      };
      const dip = {
        ...swing, templateId: 't2', name: 'Buy the dip', orderType: 'LIMIT', limitOffsetPercent: -2,
        takeProfitPercent: null, stopLossPercent: null
      };

      function loadTemplates(list: unknown[]): void {
        http.expectOne({ method: 'GET', url: templatesUrl }).flush(list);
        fixture.detectChanges();
      }

      it('lists the saved templates as one-tap chips', () => {
        loadTemplates([swing, dip]);
        const names = Array.from<HTMLElement>(fixture.nativeElement.querySelectorAll('.template-apply'))
          .map(b => b.textContent?.trim());
        expect(names).toEqual(['Swing', 'Buy the dip']);
      });

      it('shows no template row without any', () => {
        loadTemplates([]);
        expect(fixture.nativeElement.querySelector('[data-testid="templates"]')).toBeNull();
      });

      it('applies a bracket template at today\'s prices', () => {
        loadTemplates([swing]);
        (fixture.nativeElement.querySelector('.template-apply') as HTMLButtonElement).click();
        fixture.detectChanges();
        expect(component.side()).toBe('BUY');
        expect(component.orderType()).toBe('MARKET');
        expect(component.timeInForce()).toBe('GTC');
        expect(component.bracketOn()).toBe(true);
        expect(component.takeProfit()).toBe(120);
        expect(component.stopLoss()).toBe(92);
      });

      it('applies a limit template as a price against the market, without it being overwritten', () => {
        loadTemplates([dip]);
        component.bracketOn.set(true);
        (fixture.nativeElement.querySelector('.template-apply') as HTMLButtonElement).click();
        fixture.detectChanges();
        expect(component.orderType()).toBe('LIMIT');
        expect(component.limitPrice()).toBe(98);
        expect(component.bracketOn()).toBe(false);
      });

      it('saves the reviewed ticket as a template of percent offsets', () => {
        loadTemplates([]);
        pick('Limit');
        component.setLimitPrice(95);
        component.quantity.set(2);
        component.review();
        fixture.detectChanges();

        (fixture.nativeElement.querySelector('[data-testid="save-template"] .link-btn') as HTMLButtonElement).click();
        fixture.detectChanges();
        expect(component.templateName()).toBe('Buy, limit −5%, today');
        component.templateName.set('Deep dip');
        component.saveTemplate();

        const request = http.expectOne({ method: 'POST', url: templatesUrl });
        expect(request.request.body).toEqual(expect.objectContaining({
          name: 'Deep dip', side: 'BUY', orderType: 'LIMIT', timeInForce: 'DAY', limitOffsetPercent: -5
        }));
        request.flush({ ...dip, templateId: 't3', name: 'Deep dip', limitOffsetPercent: -5 });
        fixture.detectChanges();
        expect(component.templates().map(t => t.name)).toEqual(['Deep dip']);
        expect(text('[data-testid="save-template"]')).toContain('Saved “Deep dip”.');
      });

      it('says why a template could not be saved', () => {
        loadTemplates([]);
        pick('Limit');
        component.quantity.set(1);
        component.review();
        fixture.detectChanges();
        component.templateName.set('Dup');
        component.saveTemplate();
        http.expectOne({ method: 'POST', url: templatesUrl })
          .flush({ message: 'You already have a template called "Dup"' }, { status: 409, statusText: 'Conflict' });
        fixture.detectChanges();
        expect(component.templateMessage()).toEqual({ text: 'You already have a template called "Dup"', tone: 'error' });
      });

      it('deletes a template', () => {
        loadTemplates([swing, dip]);
        component.deleteTemplate(swing as never);
        http.expectOne({ method: 'DELETE', url: `${templatesUrl}/t1` }).flush(null);
        expect(component.templates().map(t => t.name)).toEqual(['Buy the dip']);
      });
    });
  });
});

