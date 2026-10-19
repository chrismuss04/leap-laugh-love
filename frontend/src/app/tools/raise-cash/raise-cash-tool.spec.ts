import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Observable, of, throwError } from 'rxjs';
import { AccountView, HoldingView } from '../../dashboard/models';
import { OpenOrder, OrderService } from '../../services/order';
import { PlanQuoteOffset } from '../../shared/order-plan';
import { ToolsContext } from '../tools-context';
import { RaiseCashToolComponent, parseAmount } from './raise-cash-tool';

describe('Raise Cash tool', () => {
  const holding = (symbol: string, quantity: number, price: number, averageCost: number): HoldingView => ({
    symbol, name: symbol, quantity, averageCost, price, previousClose: null, marketValue: price * quantity,
    dayChange: null, dayChangePercent: null, totalReturn: (price - averageCost) * quantity,
    totalReturnPercent: ((price - averageCost) / averageCost) * 100, intraday: []
  });
  // AAPL $1,500 (+50%), MSFT $2,000 (-20%), TSLA $800 (-20%), NVDA $1,000 (+25%), with $1,000 cash.
  const portfolio = () => [
    holding('AAPL', 10, 150, 100), holding('MSFT', 5, 400, 500), holding('TSLA', 4, 200, 250), holding('NVDA', 20, 50, 40)
  ];
  const order = (fields: Partial<OpenOrder>): OpenOrder => ({ accountId: 'a1', symbol: 'AAPL', side: 'SELL', quantity: 0, reservedCash: null, ...fields } as OpenOrder);

  let fixture: ComponentFixture<RaiseCashToolComponent>;
  let context: {
    account: ReturnType<typeof signal<AccountView | null>>;
    holdings: ReturnType<typeof signal<HoldingView[]>>;
    quoteOffsets: ReturnType<typeof signal<Record<string, PlanQuoteOffset>>>;
    loading: ReturnType<typeof signal<boolean>>;
    error: ReturnType<typeof signal<string | null>>;
    requestQuotes: ReturnType<typeof vi.fn>;
    load: ReturnType<typeof vi.fn>;
  };
  let openOrders: Observable<OpenOrder[]>;

  const el = (): HTMLElement => fixture.nativeElement;
  const q = <T extends Element = HTMLElement>(selector: string) => el().querySelector<T>(selector);
  const testId = <T extends Element = HTMLElement>(id: string) => q<T>(`[data-testid="${id}"]`);
  const handle = () => testId('cash-handle')!;
  const input = () => testId<HTMLInputElement>('target-input')!;
  const block = (symbol: string) => testId(`block-${symbol}`)!.textContent!.replace(/\s+/g, ' ').trim();
  const summary = () => testId('raise-summary')!.textContent!.replace(/\s+/g, ' ').trim();
  const limit = () => testId('limit-message')?.textContent?.trim() ?? null;

  async function render(): Promise<void> {
    TestBed.configureTestingModule({
      providers: [
        { provide: ToolsContext, useValue: context },
        { provide: OrderService, useValue: { getOpenOrders: () => openOrders } }
      ]
    });
    fixture = TestBed.createComponent(RaiseCashToolComponent);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  async function update(): Promise<void> {
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  async function type(text: string): Promise<void> {
    input().value = text;
    input().dispatchEvent(new Event('change'));
    await update();
  }

  async function press(key: string, shiftKey = false): Promise<void> {
    handle().dispatchEvent(new KeyboardEvent('keydown', { key, shiftKey, bubbles: true, cancelable: true }));
    await update();
  }

  async function click(element: HTMLElement): Promise<void> {
    element.click();
    await update();
  }

  async function strategy(id: 'even' | 'winners' | 'losers'): Promise<void> {
    await click(testId<HTMLInputElement>(`strategy-${id}`)!);
  }

  beforeEach(() => {
    context = {
      account: signal<AccountView | null>({ accountId: 'a1', cash: 1000 } as AccountView),
      holdings: signal(portfolio()),
      quoteOffsets: signal<Record<string, PlanQuoteOffset>>({}),
      loading: signal(false),
      error: signal<string | null>(null),
      requestQuotes: vi.fn(),
      load: vi.fn()
    };
    openOrders = of([]);
  });

  describe('setting the amount', () => {
    it('starts at current cash with nothing to sell', async () => {
      await render();
      expect(handle().getAttribute('aria-valuenow')).toBe('1000');
      expect(input().value).toBe('1000.00');
      expect(summary()).toContain('Drag the bar up or type an amount');
      expect(limit()).toBeNull();
    });

    it('starts from buying power: cash less what open buy orders hold', async () => {
      openOrders = of([order({ side: 'BUY', reservedCash: 200 })]);
      await render();
      expect(handle().getAttribute('aria-valuenow')).toBe('800');
      expect(handle().getAttribute('aria-valuemin')).toBe('800');
    });

    it('ranges up to cash plus every holding', async () => {
      await render();
      expect(handle().getAttribute('aria-valuemax')).toBe('6300');
    });

    it('moves the handle to a typed amount', async () => {
      await render();
      await type('$2,000');
      expect(handle().getAttribute('aria-valuenow')).toBe('2000');
      expect(summary()).toContain('Target $2,000.00');
    });

    it('puts a typed amount below current cash back to current cash', async () => {
      await render();
      await type('500');
      expect([handle().getAttribute('aria-valuenow'), input().value]).toEqual(['1000', '1000.00']);
    });

    it('ignores typing that is not an amount', async () => {
      await render();
      await type('2000');
      await type('lots');
      expect([handle().getAttribute('aria-valuenow'), input().value]).toEqual(['2000', '2000.00']);
    });

    it('fills in the typed amount when the handle is dragged', async () => {
      await render();
      const track = testId('cash-track')!;
      const rect = track.getBoundingClientRect();
      const at = (amount: number) => ({
        bubbles: true, pointerId: 1, pointerType: 'mouse', button: 0,
        clientX: rect.left + rect.width / 2, clientY: rect.bottom - (amount / 6300) * rect.height
      });
      track.dispatchEvent(new PointerEvent('pointerdown', at(3000)));
      track.dispatchEvent(new PointerEvent('pointerup', at(3000)));
      await update();
      expect([handle().getAttribute('aria-valuenow'), input().value]).toEqual(['3000', '3000.00']);
    });

    it('adds the quick amounts to the target', async () => {
      await render();
      await click(testId('chip-500')!);
      await click(testId('chip-1000')!);
      await click(testId('chip-100')!);
      expect(input().value).toBe('2600.00');
    });

    it('moves by the keys', async () => {
      await render();
      await press('PageUp');
      await press('ArrowUp');
      await press('ArrowUp', true);
      expect(input().value).toBe('1111.00');
    });
  });

  describe('sell strategies', () => {
    it('trims every holding evenly by default', async () => {
      await render();
      await type('2060');
      expect([block('AAPL'), block('MSFT'), block('NVDA'), block('TSLA')].map(text => text.split(' ·')[0]))
        .toEqual(['Sell 2 of 10', 'Sell 1 of 5', 'Sell 4 of 20', 'Sell 1 of 4']);
    });

    it('updates the preview as soon as the strategy changes', async () => {
      await render();
      await type('2000');
      await strategy('winners');
      expect(block('AAPL')).toContain('Sell 7 of 10');
      expect(block('MSFT')).toContain('5 shares');

      await strategy('losers');
      expect(block('MSFT')).toContain('Sell 3 of 5');
      expect(block('AAPL')).toContain('10 shares');
      expect(summary()).toContain('Target $2,000.00 → $2,200.00 in cash');
    });

    it('says when whole shares raise a little over the target', async () => {
      await render();
      await strategy('losers');
      await type('2000');
      expect(summary()).toContain('Selling raises $1,200.00, a little over, as only whole shares can be sold.');
    });

    it('never sells a locked holding', async () => {
      await render();
      await strategy('winners');
      await type('2000');
      await click(testId('lock-AAPL')!);
      expect(testId('lock-AAPL')!.getAttribute('aria-pressed')).toBe('true');
      expect(block('AAPL')).toContain('10 shares');
      expect(block('NVDA')).toContain('Sell 20 of 20');

      await click(testId('lock-AAPL')!);
      expect(block('AAPL')).toContain('Sell 7 of 10');
    });

    it('only sells shares that open sell orders have not already promised', async () => {
      openOrders = of([order({ symbol: 'AAPL', side: 'SELL', quantity: 8 })]);
      await render();
      await strategy('winners');
      await type('2000');
      expect(block('AAPL')).toContain('Sell 2 of 10');
      expect(block('NVDA')).toContain('Sell 14 of 20');
    });

    it('prices sells at the bid', async () => {
      context.quoteOffsets.set({ AAPL: { ask: 1, bid: -1 } });
      await render();
      await strategy('winners');
      await type('1149');
      expect(summary()).toContain('Selling raises $149.00');
    });

    it('asks for a quote for every holding', async () => {
      await render();
      expect(context.requestQuotes).toHaveBeenCalledWith(['AAPL', 'MSFT', 'TSLA', 'NVDA']);
    });
  });

  describe('the maximum', () => {
    it('stops the handle at the most the holdings can raise, and says why', async () => {
      await render();
      await type('10000');
      expect([handle().getAttribute('aria-valuenow'), input().value]).toEqual(['6300', '6300.00']);
      expect(limit()).toBe('You can reach at most $6,300.00 in cash: that sells everything you hold.');
    });

    it('names the locked holdings keeping it lower', async () => {
      await render();
      await click(testId('lock-AAPL')!);
      await click(testId('lock-MSFT')!);
      await type('10000');
      expect(handle().getAttribute('aria-valuenow')).toBe('2800');
      expect(handle().getAttribute('aria-valuemax')).toBe('2800');
      expect(limit()).toBe('You can reach at most $2,800.00 in cash: AAPL and MSFT are locked.');
    });

    it('mentions shares already on open sell orders', async () => {
      openOrders = of([order({ symbol: 'NVDA', side: 'SELL', quantity: 20 })]);
      await render();
      await type('10000');
      expect(limit()).toBe('You can reach at most $5,300.00 in cash: some shares are already on open sell orders.');
    });

    it('stops there by the keys too', async () => {
      await render();
      await press('End');
      expect(limit()).toBeNull();
      await press('ArrowUp');
      expect(handle().getAttribute('aria-valuenow')).toBe('6300');
      expect(limit()).toContain('You can reach at most $6,300.00');
    });

    it('drops the message once the target is back within reach', async () => {
      await render();
      await type('10000');
      await type('3000');
      expect(limit()).toBeNull();
    });
  });

  describe('edge cases', () => {
    it('disables the bar for an account with no holdings, and explains why', async () => {
      context.holdings.set([]);
      await render();
      expect(handle().getAttribute('aria-disabled')).toBe('true');
      expect(handle().hasAttribute('tabindex')).toBe(false);
      expect(input().disabled).toBe(true);
      expect(testId('no-holdings')!.textContent).toContain('no holdings to sell');
      expect(testId('raise-summary')).toBeNull();
    });

    it('leaves out a holding with no price yet, and says so', async () => {
      context.holdings.set([...portfolio(), { ...holding('GOOG', 3, 100, 90), price: null, marketValue: null }]);
      await render();
      expect(testId('unpriced')!.textContent).toContain('GOOG has no price yet');
      expect(handle().getAttribute('aria-valuemax')).toBe('6300');
    });

    it('still works when open orders cannot be loaded, and says what is not counted', async () => {
      openOrders = throwError(() => new Error('down'));
      await render();
      expect(el().textContent).toContain('Couldn’t check your open orders');
      expect(handle().getAttribute('aria-valuenow')).toBe('1000');
    });

    it('waits for the account and open orders before showing the bar', async () => {
      context.loading.set(true);
      await render();
      expect(testId('raise-cash-loading')).not.toBeNull();
      expect(handle()).toBeFalsy();
    });

    it('offers a retry when the account fails to load', async () => {
      context.error.set('Down');
      await render();
      q<HTMLButtonElement>('[role="alert"] button')!.click();
      expect(context.load).toHaveBeenCalled();
    });

    it('starts over on another account', async () => {
      await render();
      await click(testId('lock-AAPL')!);
      await type('3000');
      context.account.set({ accountId: 'a2', cash: 1000 } as AccountView);
      await update();
      expect(input().value).toBe('1000.00');
      expect(testId('lock-AAPL')!.getAttribute('aria-pressed')).toBe('false');
    });

    it('keeps the amount as prices tick', async () => {
      await render();
      await type('3000');
      context.account.set({ accountId: 'a1', cash: 1000 } as AccountView);
      await update();
      expect(input().value).toBe('3000.00');
    });
  });

  describe('parseAmount', () => {
    it('reads dollars with or without a sign, commas and spaces', () => {
      expect(['2000', '$2,000', ' 2,000.50 ', '0'].map(parseAmount)).toEqual([2000, 2000, 2000.5, 0]);
    });

    it('is null for anything else', () => {
      expect(['', 'abc', '$'].map(parseAmount)).toEqual([null, null, null]);
    });
  });
});
