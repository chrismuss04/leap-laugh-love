import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { By } from '@angular/platform-browser';
import { of, throwError } from 'rxjs';
import { SymbolSearchComponent } from '../../dashboard/symbol-search/symbol-search';
import { InstrumentOverviewRow, InstrumentsService } from '../../services/instruments';
import { MarketDataService } from '../../services/market-data';
import { ToolsContext } from '../tools-context';
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
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: ToolsContext, useValue: { prices, toolSymbols } },
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
      expect(search('AAPL')[0].textContent).toContain('In basket');
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
      expect(mover('AAPL').querySelector('button.add')!.textContent!.trim()).toBe('In basket');
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
});
