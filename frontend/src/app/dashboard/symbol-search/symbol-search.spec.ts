import { ComponentFixture, TestBed, fakeAsync, tick } from '@angular/core/testing';
import { Subject, of } from 'rxjs';
import { LatestPrice, MarketDataService } from '../../services/market-data';
import { SymbolSearchComponent } from './symbol-search';
import type { MockedObject } from 'vitest';
import { createSpyObj } from '../../../testing/create-spy-obj';

describe('SymbolSearchComponent', () => {
  const catalog: LatestPrice[] = [
    { symbol: 'MSFT', name: 'Microsoft', price: 300, asOf: '' },
    { symbol: 'AAPL', name: 'Apple', price: 150, asOf: '' },
    { symbol: 'APLE', name: 'Apple Hospitality', price: 15, asOf: '' },
    { symbol: 'XAP', name: null, price: 1, asOf: '' }
  ];

  let fixture: ComponentFixture<SymbolSearchComponent>;
  let search: SymbolSearchComponent;
  let market: MockedObject<MarketDataService>;
  let picks: string[];

  const key = (name: string) => {
    const event = new KeyboardEvent('keydown', { key: name, cancelable: true });
    search.onKey(event);
    return event;
  };

  function type(query: string): void {
    search.query = query;
    search.filter();
  }

  beforeEach(() => {
    market = createSpyObj('MarketDataService', ['getAllLatestPrices']);
    market.getAllLatestPrices.mockReturnValue(of(catalog));
    TestBed.configureTestingModule({
      imports: [SymbolSearchComponent],
      providers: [{ provide: MarketDataService, useValue: market }]
    });
    fixture = TestBed.createComponent(SymbolSearchComponent);
    search = fixture.componentInstance;
    fixture.componentRef.setInput('names', { MSFT: 'Microsoft', AAPL: 'Apple', APLE: 'Apple Hospitality' });
    fixture.componentRef.setInput('livePrices', { AAPL: 175 });
    fixture.detectChanges();
    picks = [];
    search.pick.subscribe(symbol => picks.push(symbol));
  });

  it('loads the catalog once, on first focus', () => {
    search.onFocus();
    search.onFocus();
    expect(market.getAllLatestPrices).toHaveBeenCalledTimes(1);
    expect(search.open()).toBe(true);
    expect(search.loading).toBe(false);
  });

  it('does not load again while a load is in flight', () => {
    market.getAllLatestPrices.mockReturnValue(new Subject());
    search.onFocus();
    search.onFocus();
    expect(market.getAllLatestPrices).toHaveBeenCalledTimes(1);
    expect(search.loading).toBe(true);
  });

  it('shows no results for an empty query', () => {
    search.onFocus();
    type('   ');
    expect(search.results).toEqual([]);
  });

  it('matches symbol or name, ranking exact then prefix then the rest', () => {
    search.onFocus();
    type('ap');
    expect(search.results.map(r => r.symbol)).toEqual(['APLE', 'AAPL', 'XAP']);
    type('apple');
    expect(search.results.map(r => r.symbol)).toEqual(['AAPL', 'APLE']);
    type('aapl');
    expect(search.results[0].symbol).toBe('AAPL');
  });

  it('prefers the live price and tolerates a missing name', () => {
    search.onFocus();
    type('aapl');
    expect(search.results[0].price).toBe(175);
    type('xap');
    expect(search.results[0]).toEqual({ symbol: 'XAP', name: null, price: 1 });
  });

  it('limits results to eight', () => {
    market.getAllLatestPrices.mockReturnValue(of(
      Array.from({ length: 12 }, (_, i) => ({ symbol: `T${i}`, name: null, price: i, asOf: '' }))));
    search.onFocus();
    type('t');
    expect(search.results.length).toBe(8);
  });

  it('moves the highlight with the arrow keys, wrapping around', () => {
    search.onFocus();
    type('ap');
    key('ArrowDown');
    expect(search.highlighted()).toBe(1);
    key('ArrowUp');
    key('ArrowUp');
    expect(search.highlighted()).toBe(search.results.length - 1);
    key('ArrowDown');
    expect(search.highlighted()).toBe(0);
  });

  it('ignores arrows and Enter without results', () => {
    expect(key('ArrowDown').defaultPrevented).toBe(false);
    expect(key('ArrowUp').defaultPrevented).toBe(false);
    expect(key('Enter').defaultPrevented).toBe(false);
    expect(picks).toEqual([]);
  });

  it('picks the highlighted result on Enter and resets', () => {
    search.onFocus();
    type('msft');
    key('Enter');
    expect(picks).toEqual(['MSFT']);
    expect(search.query).toBe('');
    expect(search.results).toEqual([]);
    expect(search.open()).toBe(false);
  });

  it('closes on Escape', () => {
    search.onFocus();
    key('Escape');
    expect(search.open()).toBe(false);
  });

  it('closes shortly after blur, so a click can land first', fakeAsync(() => {
    search.onFocus();
    search.onBlur();
    expect(search.open()).toBe(true);
    tick(150);
    expect(search.open()).toBe(false);
  }));

  it('focuses on "/" unless already typing', () => {
    const input = fixture.nativeElement.querySelector('input') as HTMLInputElement;
    const press = (target: EventTarget) => {
      const event = new KeyboardEvent('keydown', { key: '/', cancelable: true });
      Object.defineProperty(event, 'target', { value: target });
      search.onGlobalKey(event);
      return event;
    };
    expect(press(document.body).defaultPrevented).toBe(true);
    expect(press(input).defaultPrevented).toBe(false);
    expect(press(null as unknown as EventTarget).defaultPrevented).toBe(true);
  });

  it('renders results and lets one be clicked', () => {
    search.onFocus();
    search.query = 'msft';
    search.filter();
    fixture.detectChanges();
    const result = fixture.nativeElement.querySelector('li.result') as HTMLElement;
    expect(result.textContent).toContain('MSFT');
    result.click();
    expect(picks).toEqual(['MSFT']);
  });

  it('says when nothing matches', () => {
    search.onFocus();
    search.query = 'zzzz';
    search.filter();
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('No symbols match');
  });
});
