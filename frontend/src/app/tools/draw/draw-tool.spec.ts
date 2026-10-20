import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';
import { MarketDataService } from '../../services/market-data';
import { ToolsContext } from '../tools-context';
import { DrawToolComponent, describeIdea } from './draw-tool';
import { interpret } from './interpret';

describe('DrawToolComponent', () => {
  let fixture: ComponentFixture<DrawToolComponent>;
  let history: ReturnType<typeof vi.fn>;
  const context = {
    loading: signal(false),
    holdings: signal([{ symbol: 'MSFT', quantity: 4 }, { symbol: 'AAPL', quantity: 10 }]),
    prices: signal<Record<string, number>>({ MSFT: 402 }),
    toolSymbols: signal<string[]>([])
  };
  const candles = (...closes: number[]) => closes.map(close => ({ close }));
  const el = () => fixture.nativeElement as HTMLElement;

  beforeEach(() => {
    history = vi.fn().mockReturnValue(of(candles(400, 401, 399, 402)));
    TestBed.configureTestingModule({
      providers: [
        { provide: ToolsContext, useValue: context },
        { provide: MarketDataService, useValue: { getHistory: history } }
      ]
    });
    fixture = TestBed.createComponent(DrawToolComponent);
    fixture.detectChanges();
    TestBed.tick();
    fixture.detectChanges();
  });

  it('starts on the largest holding, live, and watches its price', () => {
    expect(el().querySelector('[aria-checked="true"][data-symbol]')!.textContent).toBe('MSFT');
    expect(el().querySelector('[data-testid="draw-price"]')!.textContent).toBe('$402.00');
    expect(context.toolSymbols()).toEqual(['MSFT']);
    const [symbol, , , interval, size] = history.mock.calls[0];
    expect([symbol, interval, size]).toEqual(['MSFT', 300, 96]);
    expect(fixture.componentInstance.history()).toEqual([400, 401, 399, 402]);
  });

  it('reloads for another stock or horizon', () => {
    el().querySelector<HTMLButtonElement>('[data-symbol="AAPL"]')!.click();
    el().querySelector<HTMLButtonElement>('[data-horizon="1W"]')!.click();
    fixture.detectChanges();
    TestBed.tick();
    expect(history.mock.calls.at(-1)!.slice(0, 1).concat(history.mock.calls.at(-1)!.slice(3))).toEqual(['AAPL', 3600, 72]);
  });

  it('says so when the history doesn\'t load, and tries again', () => {
    history.mockReturnValue(throwError(() => new Error('down')));
    fixture.componentInstance.reload();
    TestBed.tick();
    fixture.detectChanges();
    expect(el().querySelector('[role="alert"]')!.textContent).toContain("Couldn't load MSFT");
  });

  it('reads a drawn line as a trade, knowing what the account holds', () => {
    fixture.componentInstance.read([{ step: 0, price: 402 }, { step: 10, price: 380 }]);
    fixture.detectChanges();
    expect(fixture.componentInstance.idea()).toEqual(expect.objectContaining({ shape: 'fall', needsShares: false }));
    expect(el().querySelector('[data-testid="draw-reading"]')!.textContent).toContain('Sell MSFT now');

    fixture.componentInstance.adjust({ level: 'target', price: 370 });
    expect(fixture.componentInstance.idea()!.target).toBe(370);
  });

  it('says each idea in a sentence', () => {
    const say = (prices: number[], held = 0) =>
      describeIdea('AAPL', interpret(prices.map((price, step) => ({ step, price })), prices[0], { held })!);
    expect(say([100, 110])).toBe('Buy AAPL now. Take profit at $110.00, stop loss at $95.00.');
    expect(say([100, 94, 112])).toBe('Buy AAPL if it dips to $94.00. Take profit at $112.00, stop loss at $85.00.');
    expect(say([100, 108, 95], 3)).toBe('Sell AAPL if it rises to $108.00, before it falls back to $95.00.');
    expect(say([100, 90])).toContain("you'd need to own it");
    expect(say([100, 100.1])).toContain('stays flat');
  });
});
