import { inject, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { By } from '@angular/platform-browser';
import { of, throwError } from 'rxjs';
import { SymbolSearchComponent } from '../../dashboard/symbol-search/symbol-search';
import { InstrumentOverviewRow, InstrumentsService } from '../../services/instruments';
import { MarketDataService } from '../../services/market-data';
import { ToolsContext } from '../tools-context';
import { TradePlanService } from '../trade-plan';
import { BasketService, MAX_BASKET } from './basket';
import { BASKET_DRAG_TYPE, BasketToolComponent } from './basket-tool';

describe('BasketToolComponent', () => {
  const row = (symbol: string, name: string, price: number, baselinePrice: number | null): InstrumentOverviewRow => ({
    symbol, name, assetClass: 'EQUITY', currency: 'USD', price, asOf: '', baselinePrice,
    change: baselinePrice === null ? null : price - baselinePrice,
    changePercent: baselinePrice === null ? null : ((price - baselinePrice) / baselinePrice) * 100
  });
  const overview = [
    row('AAPL', 'Apple', 105, 100),
    row('NVDA', 'Nvidia', 103, 100),
    row('MSFT', 'Microsoft', 98, 100),
    row('SPX', 'S&P 500', 110, 100),
    row('F', 'Ford', 12, null)
  ];

  let fixture: ComponentFixture<BasketToolComponent>;
  let basket: BasketService;
  let prices: ReturnType<typeof signal<Record<string, number>>>;
  let toolSymbols: ReturnType<typeof signal<string[]>>;
  let getOverview: ReturnType<typeof vi.fn>;
  let quoteOffsets: ReturnType<typeof signal<Record<string, { ask: number; bid: number }>>>;
  let account: ReturnType<typeof signal<{ accountId: string; cash: number } | null>>;
  let requestQuotes: ReturnType<typeof vi.fn>;

  const el = (): HTMLElement => fixture.nativeElement;
  const q = <T extends HTMLElement = HTMLElement>(selector: string) => el().querySelector<T>(selector);
  const basketSymbols = () => Array.from(el().querySelectorAll<HTMLElement>('.rows li')).map(li => li.dataset['symbol']);
  const mover = (symbol: string) => q(`.mover[data-symbol="${symbol}"]`)!;
  const notice = () => q('[data-testid="basket-notice"]')?.textContent?.trim() ?? null;

  /** A fresh page: a refresh, as far as the basket can tell. */
  function configure(): void {
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      imports: [BasketToolComponent],
      providers: [
        BasketService,
        TradePlanService,
        provideHttpClient(),
        provideHttpClientTesting(),
        {
          provide: ToolsContext,
          useFactory: () => ({
            prices, toolSymbols, quoteOffsets, account, requestQuotes, holdings: () => [], plan: inject(TradePlanService)
          })
        },
        { provide: InstrumentsService, useValue: { getOverview } },
        {
          provide: MarketDataService,
          useValue: { getAllLatestPrices: () => of(overview.map(r => ({ symbol: r.symbol, name: r.name, price: r.price, asOf: '' }))) }
        }
      ]
    });
  }

  function render(): void {
    fixture = TestBed.createComponent(BasketToolComponent);
    basket = TestBed.inject(BasketService);
    fixture.detectChanges();
  }

  /** Drags a mover row onto the basket, the way a browser fires the events. */
  function drag(symbol: string): void {
    const transfer = new DataTransfer();
    mover(symbol).dispatchEvent(new DragEvent('dragstart', { bubbles: true, cancelable: true, dataTransfer: transfer }));
    const drop = q('[data-testid="basket-drop"]')!;
    drop.dispatchEvent(new DragEvent('dragover', { bubbles: true, cancelable: true, dataTransfer: transfer }));
    drop.dispatchEvent(new DragEvent('drop', { bubbles: true, cancelable: true, dataTransfer: transfer }));
    fixture.detectChanges();
  }

  /** Picks a symbol in the search the way a click on a result does. */
  function search(query: string): HTMLElement[] {
    const input = fixture.debugElement.query(By.directive(SymbolSearchComponent)).componentInstance as SymbolSearchComponent;
    input.onFocus();
    input.query = query;
    input.filter();
    fixture.detectChanges();
    return Array.from(el().querySelectorAll<HTMLElement>('li.result'));
  }

  beforeEach(() => {
    sessionStorage.clear();
    prices = signal<Record<string, number>>({});
    toolSymbols = signal<string[]>([]);
    quoteOffsets = signal<Record<string, { ask: number; bid: number }>>({});
    account = signal<{ accountId: string; cash: number } | null>({ accountId: 'a1', cash: 10000 });
    requestQuotes = vi.fn();
    getOverview = vi.fn(() => of({ period: 'today', periodStart: '', asOf: '', instruments: overview }));
    configure();
    render();
  });

  afterEach(() => sessionStorage.clear());

  it('starts empty and explains how to add stocks', () => {
    expect(q('[data-testid="basket-empty"]')!.textContent).toContain('A basket holds 2 to 10 stocks.');
    expect(q('[data-testid="basket-count"]')!.textContent).toBe('0 / 10');
  });

  describe('adding by search', () => {
    it('adds the picked stock', () => {
      search('AAPL')[0].click();
      fixture.detectChanges();
      expect(basketSymbols()).toEqual(['AAPL']);
      expect(basket.items()[0]).toEqual({ symbol: 'AAPL', name: 'Apple' });
    });

    it('marks stocks it can\'t add, and won\'t pick them', () => {
      basket.add('AAPL', 'Apple');
      fixture.detectChanges();
      expect(search('AAPL')[0].textContent).toContain('Already in basket');
      const index = search('SPX')[0];
      expect(index.textContent).toContain('Not tradable');
      index.click();
      fixture.detectChanges();
      expect(basketSymbols()).toEqual(['AAPL']);
    });
  });

  describe('adding by drag', () => {
    it('adds a mover dropped on the basket', () => {
      drag('NVDA');
      expect(basketSymbols()).toEqual(['NVDA']);
      expect(basket.items()[0].name).toBe('Nvidia');
    });

    it('ignores a drop that isn\'t a mover', () => {
      const transfer = new DataTransfer();
      transfer.setData('text/plain', 'AAPL');
      const over = new DragEvent('dragover', { bubbles: true, cancelable: true, dataTransfer: transfer });
      q('[data-testid="basket-drop"]')!.dispatchEvent(over);
      q('[data-testid="basket-drop"]')!.dispatchEvent(new DragEvent('drop', { bubbles: true, cancelable: true, dataTransfer: transfer }));
      fixture.detectChanges();
      expect(over.defaultPrevented).toBe(false);
      expect(basket.isEmpty()).toBe(true);
    });

    it('highlights the basket while a mover is over it', () => {
      const transfer = new DataTransfer();
      transfer.setData(BASKET_DRAG_TYPE, 'AAPL');
      q('[data-testid="basket-drop"]')!.dispatchEvent(new DragEvent('dragover', { bubbles: true, cancelable: true, dataTransfer: transfer }));
      fixture.detectChanges();
      expect(q('[data-testid="basket-drop"]')!.classList).toContain('over');
    });

    it('also adds a mover with its Add button', () => {
      mover('MSFT').querySelector<HTMLButtonElement>('button.add')!.click();
      fixture.detectChanges();
      expect(basketSymbols()).toEqual(['MSFT']);
    });
  });

  describe('limits', () => {
    it('refuses an 11th stock with a message', () => {
      for (let i = 0; i < MAX_BASKET; i++) {
        basket.add(`S${i}`, `Stock ${i}`);
      }
      fixture.detectChanges();
      expect(mover('AAPL').getAttribute('draggable')).toBe('false');
      expect(mover('AAPL').querySelector<HTMLButtonElement>('button.add')!.disabled).toBe(true);
      expect(mover('AAPL').querySelector('.label-short')!.textContent!.trim()).toBe('Full');
      expect(search('AAPL')[0].textContent).toContain('Basket full');

      // A drag that was already under way when the basket filled up.
      const transfer = new DataTransfer();
      transfer.setData(BASKET_DRAG_TYPE, 'AAPL');
      q('[data-testid="basket-drop"]')!.dispatchEvent(new DragEvent('drop', { bubbles: true, cancelable: true, dataTransfer: transfer }));
      fixture.detectChanges();
      expect(notice()).toBe('A basket holds up to 10 stocks. Remove one to add another.');
      expect(basket.count()).toBe(MAX_BASKET);
    });

    it('refuses a duplicate with a message', () => {
      drag('AAPL');
      const transfer = new DataTransfer();
      transfer.setData(BASKET_DRAG_TYPE, 'AAPL');
      q('[data-testid="basket-drop"]')!.dispatchEvent(new DragEvent('drop', { bubbles: true, cancelable: true, dataTransfer: transfer }));
      fixture.detectChanges();
      expect(notice()).toBe('AAPL is already in your basket.');
      expect(basketSymbols()).toEqual(['AAPL']);
      const button = mover('AAPL').querySelector('button.add')!;
      expect(button.querySelector('.label-long')!.textContent!.trim()).toBe('Already in basket');
      // Narrow screens show a short label; the button's name stays whole for screen readers.
      expect(button.querySelector('.label-short')!.textContent!.trim()).toBe('✓ Added');
      expect(button.getAttribute('aria-label')).toBe('AAPL: Already in basket');
    });

    it('asks for a second stock before the basket is ready', () => {
      drag('AAPL');
      expect(q('[data-testid="basket-min"]')).not.toBeNull();
      drag('MSFT');
      expect(q('[data-testid="basket-min"]')).toBeNull();
    });
  });

  describe('each stock', () => {
    it('shows its live price and today\'s change', () => {
      drag('AAPL');
      // Ford isn't a mover: it has no baseline today.
      basket.add('F', 'Ford');
      fixture.detectChanges();
      const rowOf = (symbol: string) => q(`.rows li[data-symbol="${symbol}"]`)!;
      expect(rowOf('AAPL').querySelector('[data-testid="basket-price"]')!.textContent).toContain('105.00');
      expect(rowOf('AAPL').querySelector('[data-testid="basket-change"]')!.textContent).toContain('5.00%');

      prices.set({ AAPL: 110 });
      fixture.detectChanges();
      expect(rowOf('AAPL').querySelector('[data-testid="basket-price"]')!.textContent).toContain('110.00');
      expect(rowOf('AAPL').querySelector('[data-testid="basket-change"]')!.textContent).toContain('10.00%');
      expect(rowOf('F').querySelector('[data-testid="basket-change"]')!.textContent!.trim()).toBe('—');
    });

    it('can be removed', () => {
      drag('AAPL');
      drag('MSFT');
      q<HTMLButtonElement>('.rows li[data-symbol="AAPL"] [data-testid="basket-remove"]')!.click();
      fixture.detectChanges();
      expect(basketSymbols()).toEqual(['MSFT']);
    });

    it('stays on the live price stream, with the movers', () => {
      basket.add('F', 'Ford');
      fixture.detectChanges();
      expect(new Set(toolSymbols())).toEqual(new Set(['F', 'AAPL', 'NVDA', 'MSFT']));
    });
  });

  describe('movers', () => {
    it('lists today\'s gainers and losers among tradable stocks only', () => {
      const group = (key: string) => Array.from(el().querySelectorAll<HTMLElement>(`[data-group="${key}"] .mover`)).map(m => m.dataset['symbol']);
      expect(group('gainers')).toEqual(['AAPL', 'NVDA']);
      expect(group('losers')).toEqual(['MSFT']);
    });

    it('can be retried when they fail to load', () => {
      getOverview.mockReturnValueOnce(throwError(() => new Error('down')));
      configure();
      render();
      expect(q('[data-testid="movers-error"]')).not.toBeNull();
      q<HTMLButtonElement>('[data-testid="movers-error"] button')!.click();
      fixture.detectChanges();
      expect(q('[data-testid="movers-error"]')).toBeNull();
      expect(mover('AAPL')).not.toBeNull();
    });
  });

  describe('clearing', () => {
    beforeEach(() => {
      drag('AAPL');
      drag('MSFT');
      q<HTMLButtonElement>('[data-testid="basket-clear"]')!.click();
      fixture.detectChanges();
    });

    it('asks first, and keeps the basket on Cancel', () => {
      expect(q('[data-testid="clear-confirm"]')!.textContent).toContain('Remove all 2 stocks from your basket?');
      q<HTMLButtonElement>('[data-testid="clear-no"]')!.click();
      fixture.detectChanges();
      expect(basketSymbols()).toEqual(['AAPL', 'MSFT']);
      expect(q('[data-testid="clear-confirm"]')).toBeNull();
    });

    it('empties the basket once confirmed', () => {
      q<HTMLButtonElement>('[data-testid="clear-yes"]')!.click();
      fixture.detectChanges();
      expect(basket.isEmpty()).toBe(true);
      expect(q('[data-testid="basket-empty"]')).not.toBeNull();
    });
  });

  it('keeps the basket across a refresh', () => {
    drag('AAPL');
    drag('MSFT');
    TestBed.tick();
    fixture.destroy();
    configure();
    render();
    expect(basketSymbols()).toEqual(['AAPL', 'MSFT']);
  });
  describe('splitting an amount', () => {
    const plan = () => TestBed.inject(TradePlanService);
    const rowOf = (symbol: string) => q(`.rows li[data-symbol="${symbol}"]`)!;
    const rowAmount = (symbol: string) => rowOf(symbol).querySelector<HTMLInputElement>('[data-testid="row-amount"]')!;
    const rowShares = (symbol: string) => rowOf(symbol).querySelector('[data-testid="row-shares"]')!.textContent!.trim();
    const amountInput = () => q<HTMLInputElement>('[data-testid="basket-amount"]')!;
    const addButton = () => q<HTMLButtonElement>('[data-testid="basket-add"]');
    const planLines = () => plan().sorted().map(o => `${o.side} ${o.quantity} ${o.symbol} (${o.source})`);

    function type(input: HTMLInputElement, value: string): void {
      input.value = value;
      input.dispatchEvent(new Event('change'));
      fixture.detectChanges();
    }

    beforeEach(() => {
      // AAPL 105, NVDA 103, MSFT 98 from today's snapshot.
      drag('AAPL');
      drag('NVDA');
      drag('MSFT');
    });

    it('needs two stocks before there is anything to split', () => {
      basket.remove('NVDA');
      basket.remove('MSFT');
      fixture.detectChanges();
      expect(q('[data-testid="basket-split"]')).toBeNull();
    });

    it('starts with no amount, so nothing can go into the plan yet', () => {
      expect(amountInput().value).toBe('');
      expect(addButton()!.disabled).toBe(true);
      expect(q('[data-testid="basket-max"]')!.textContent).toContain('$9,950.24');
    });

    it('splits a typed amount evenly', () => {
      type(amountInput(), '1,000');
      expect(basket.totalCents()).toBe(100000);
      expect(['AAPL', 'NVDA', 'MSFT'].map(s => rowAmount(s).value)).toEqual(['333.34', '333.33', '333.33']);
    });

    it('sets the amount from the slider', () => {
      const slider = q<HTMLInputElement>('[data-testid="basket-slider"]')!;
      slider.value = '250000';
      slider.dispatchEvent(new Event('input'));
      fixture.detectChanges();
      expect(amountInput().value).toBe('2500.00');
    });

    it('brings an amount over the cash available down to it, and says so', () => {
      type(amountInput(), '50000');
      expect(basket.totalCents()).toBe(995024);
      expect(q('[data-testid="amount-note"]')!.textContent).toContain('You can invest up to $9,950.24.');
    });

    it('lowers the amount when the cash available falls below it', () => {
      type(amountInput(), '9000');
      account.set({ accountId: 'a1', cash: 5000 });
      TestBed.tick();
      fixture.detectChanges();
      expect(basket.totalCents()).toBe(497512);
      expect(q('[data-testid="amount-note"]')!.textContent).toContain('lowered to $4,975.12');
    });

    it('leaves room for buys other tools put in the plan', () => {
      prices.set({ F: 12 });
      plan().addPlan('rebalance', 'a1', [{ symbol: 'F', side: 'BUY', quantity: 100 }]);
      fixture.detectChanges();
      // (10,000 / 1.005) - 100 x 12 = 8,750.24
      expect(q('[data-testid="basket-max"]')!.textContent).toContain('$8,750.24');
    });

    it('moves 1% between neighbours with the arrow keys', () => {
      type(amountInput(), '1000');
      const divider = q('[data-divider="0"]')!;
      divider.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowRight', bubbles: true }));
      fixture.detectChanges();
      expect(basket.amounts()).toEqual([34334, 32333, 33333]);
      expect(divider.getAttribute('aria-valuetext')).toBe('AAPL 34%, NVDA 32%');
    });

    it('keeps the total when one stock\'s amount is typed', () => {
      type(amountInput(), '1000');
      type(rowAmount('AAPL'), '500');
      expect(basket.amounts()).toEqual([50000, 25000, 25000]);
      expect(basket.amounts().reduce((a, b) => a + b)).toBe(100000);
    });

    it('rounds each amount down to whole shares and shows what is left', () => {
      type(amountInput(), '1000');
      // 333.34 / 105 = 3 shares (315.00), 18.34 left.
      expect(rowShares('AAPL')).toBe('3 shares at $105.00 · $18.34 left');
      // Invested 315 + 309 + 294.
      expect(q('[data-testid="basket-totals"]')!.textContent).toContain('$918.00');
      expect(q('[data-testid="basket-totals"]')!.textContent).toContain('$82.00');
    });

    it('prices buys at the ask once the quote is in', () => {
      quoteOffsets.set({ AAPL: { ask: 6.2, bid: -0.1 } });
      type(amountInput(), '1000');
      // 333.34 / 111.20 = 2 shares.
      expect(rowShares('AAPL')).toBe('2 shares at $111.20 · $110.94 left');
    });

    it('flags a stock whose amount doesn\'t reach one share', () => {
      type(amountInput(), '1000');
      type(rowAmount('MSFT'), '50');
      expect(rowOf('MSFT').querySelector('[data-testid="row-not-enough"]')!.textContent).toBe('Not enough for 1 share');
      expect(q('[data-testid="left-out"]')!.textContent).toContain('MSFT');
    });

    describe('adding to the plan', () => {
      it('sends one buy per stock with shares, tagged Basket, leaving out any without', () => {
        type(amountInput(), '300');
        type(rowAmount('MSFT'), '0');
        // AAPL 150 / 105 = 1 (45 left), NVDA 150 / 103 = 1 (47 left): 92 left, too little for another share.
        addButton()!.click();
        fixture.detectChanges();
        expect(q('[data-testid="leftover-prompt"]')).toBeNull();
        expect(planLines()).toEqual(['BUY 1 AAPL (basket)', 'BUY 1 NVDA (basket)']);
        expect(q('[data-testid="added-note"]')!.textContent!.trim())
          .toBe('2 buys added to your plan: $208.00 invested, $92.00 left over. Left out, not enough for 1 share: MSFT.');
      });

      it('replaces the Basket buys it added before', () => {
        // $1,000 leaves $82.00 and $400 leaves $94.00: neither buys another share, so no prompt.
        type(amountInput(), '1000');
        addButton()!.click();
        fixture.detectChanges();
        expect(planLines()).toEqual(['BUY 3 AAPL (basket)', 'BUY 3 MSFT (basket)', 'BUY 3 NVDA (basket)']);
        type(amountInput(), '400');
        addButton()!.click();
        fixture.detectChanges();
        expect(planLines()).toEqual(['BUY 1 AAPL (basket)', 'BUY 1 MSFT (basket)', 'BUY 1 NVDA (basket)']);
      });

      it('asks before spending the leftover, and never more than the amount chosen', () => {
        type(amountInput(), '5000');
        // 1666.67 / 105 = 15 (91.67 left), 1666.67 / 103 = 16 (18.67), 1666.66 / 98 = 17 (0.66): 111.00 left.
        addButton()!.click();
        fixture.detectChanges();
        const prompt = q('[data-testid="leftover-prompt"]')!.textContent!.replace(/\s+/g, ' ');
        expect(prompt).toContain('You have $111.00 left over. It could buy +1 MSFT ($98.00), leaving $13.00.');
        expect(plan().isEmpty()).toBe(true);

        q<HTMLButtonElement>('[data-testid="use-leftovers"]')!.click();
        fixture.detectChanges();
        expect(planLines()).toEqual(['BUY 15 AAPL (basket)', 'BUY 18 MSFT (basket)', 'BUY 16 NVDA (basket)']);
        // 15 x 105 + 18 x 98 + 16 x 103 = 4,987: within the 5,000 chosen.
        expect(q('[data-testid="added-note"]')!.textContent!.trim())
          .toBe('3 buys added to your plan, using leftovers: $4,987.00 invested, $13.00 left over.');
      });

      it('adds without the extras when asked to', () => {
        type(amountInput(), '5000');
        addButton()!.click();
        fixture.detectChanges();
        q<HTMLButtonElement>('[data-testid="add-without"]')!.click();
        fixture.detectChanges();
        expect(planLines()).toEqual(['BUY 15 AAPL (basket)', 'BUY 17 MSFT (basket)', 'BUY 16 NVDA (basket)']);
      });

      it('adds nothing on Cancel', () => {
        type(amountInput(), '5000');
        addButton()!.click();
        fixture.detectChanges();
        q<HTMLButtonElement>('[data-testid="offer-cancel"]')!.click();
        fixture.detectChanges();
        expect(plan().isEmpty()).toBe(true);
        expect(addButton()).not.toBeNull();
      });
    });
  });
});
