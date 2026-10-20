import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';
import { MarketDataService } from '../../services/market-data';
import { MarketStatusService } from '../../services/market-status';
import { OrderLine } from '../../shared/order-plan';
import { ToolsContext } from '../tools-context';
import { DrawToolComponent } from './draw-tool';

describe('DrawToolComponent', () => {
  let fixture: ComponentFixture<DrawToolComponent>;
  let tool: DrawToolComponent;
  let history: ReturnType<typeof vi.fn>;
  let session: ReturnType<typeof signal<string | null>>;
  let context: ReturnType<typeof makeContext>;

  function makeContext() {
    const orders = signal<(OrderLine & { id: string; source: string })[]>([]);
    return {
      loading: signal(false),
      holdings: signal([{ symbol: 'MSFT', quantity: 4 }, { symbol: 'AAPL', quantity: 10 }]),
      prices: signal<Record<string, number>>({ MSFT: 402 }),
      toolSymbols: signal<string[]>([]),
      account: signal<{ accountId: string; cash: number } | null>({ accountId: 'a1', cash: 10_000 }),
      plan: {
        orders,
        addPlan: vi.fn((source: string, _account: string, lines: OrderLine[]) =>
          orders.set(lines.map((l, i) => ({ ...l, id: `o${i}`, source }))))
      }
    };
  }

  const el = () => fixture.nativeElement as HTMLElement;
  const text = (testId: string) => el().querySelector(`[data-testid="${testId}"]`)?.textContent?.replace(/\s+/g, ' ').trim();
  const addButton = () => el().querySelector<HTMLButtonElement>('[data-testid="draw-add"]');
  const draw = (...prices: number[]) => {
    tool.read(prices.map((price, i) => ({ step: i * 10, price })));
    fixture.detectChanges();
  };

  beforeEach(() => {
    context = makeContext();
    session = signal<string | null>('REGULAR');
    history = vi.fn().mockReturnValue(of([400, 401, 399, 402].map(close => ({ close }))));
    TestBed.configureTestingModule({
      providers: [
        { provide: ToolsContext, useValue: context },
        { provide: MarketDataService, useValue: { getHistory: history, getAllLatestPrices: () => of([]) } },
        { provide: MarketStatusService, useValue: { session } }
      ]
    });
    fixture = TestBed.createComponent(DrawToolComponent);
    tool = fixture.componentInstance;
    fixture.detectChanges();
    TestBed.tick();
    fixture.detectChanges();
  });

  describe('the chart', () => {
    it('starts on the largest holding, live, and watches its price', () => {
      expect(el().querySelector('[aria-checked="true"][data-symbol]')!.textContent).toBe('MSFT');
      expect(text('draw-price')).toBe('$402.00');
      expect(context.toolSymbols()).toEqual(['MSFT']);
      const [symbol, , , interval, size] = history.mock.calls[0];
      expect([symbol, interval, size]).toEqual(['MSFT', 300, 96]);
      expect(tool.history()).toEqual([400, 401, 399, 402]);
    });

    it('reloads for another stock or horizon, and offers a searched stock beside the holdings', () => {
      tool.pick('NVDA');
      el().querySelector<HTMLButtonElement>('[data-horizon="1W"]')!.click();
      fixture.detectChanges();
      TestBed.tick();
      const last = history.mock.calls.at(-1)!;
      expect([last[0], last[3], last[4]]).toEqual(['NVDA', 3600, 72]);
      expect(tool.symbols()).toEqual(['NVDA', 'MSFT', 'AAPL']);
    });

    it('says so when the history doesn\'t load', () => {
      history.mockReturnValue(throwError(() => new Error('down')));
      tool.reload();
      TestBed.tick();
      fixture.detectChanges();
      expect(el().querySelector('[role="alert"]')!.textContent).toContain("Couldn't load MSFT");
    });
  });

  describe('a buy', () => {
    beforeEach(() => draw(402, 430));

    it('lays out the trade at a quarter of what the cash buys', () => {
      expect(text('draw-sentence')).toBe('Buy 6 MSFT now at about $402.00. Sell at $430.00 for +$168.00, or bail at $388.00 for −$84.00.');
      expect(text('draw-size')).toContain('6 · $2,412.00 of $10,000.00 cash');
      expect(text('draw-ratio')).toBe('2.0 : 1');
      expect(text('draw-terms')).toBe('Take profit $430.00 / stop loss $388.00');
    });

    it('trades at the live price, not the one drawn from', () => {
      context.prices.set({ MSFT: 410 });
      fixture.detectChanges();
      expect(text('draw-sentence')).toContain('now at about $410.00');
    });

    it('resizes from the slider and the quick sizes', () => {
      tool.setShares(20);
      fixture.detectChanges();
      expect(text('draw-sentence')).toContain('Buy 20 MSFT');
      el().querySelector<HTMLButtonElement>('.step:last-child')!.click();
      fixture.detectChanges();
      expect(tool.shares()).toBe(24);
      tool.setShares(500);
      expect(tool.shares()).toBe(24);
    });

    it('adds the order with its bracket, then says it\'s in the plan until it changes', () => {
      addButton()!.click();
      fixture.detectChanges();
      expect(context.plan.addPlan).toHaveBeenCalledWith('draw', 'a1', [
        { symbol: 'MSFT', side: 'BUY', quantity: 6, bracket: { takeProfit: 430, stopLoss: 388 } }
      ]);
      expect(addButton()!.textContent!.trim()).toBe('In your plan ✓');
      expect(addButton()!.disabled).toBe(true);

      tool.adjust({ level: 'target', price: 440 });
      fixture.detectChanges();
      expect(addButton()!.textContent!.trim()).toBe('Update plan');
      expect(addButton()!.disabled).toBe(false);
    });

    it('says when the market is closed', () => {
      session.set('CLOSED');
      fixture.detectChanges();
      expect(text('draw-notice')).toContain('waits for the next open');
    });

    it('says when the cash won\'t buy a share', () => {
      context.account.set({ accountId: 'a1', cash: 100 });
      fixture.detectChanges();
      expect(text('draw-notice')).toBe('Not enough cash for 1 share at $402.00.');
      expect(addButton()).toBeNull();
    });
  });

  it('keeps a dip buy as a limit order for the horizon', () => {
    el().querySelector<HTMLButtonElement>('[data-horizon="1M"]')!.click();
    fixture.detectChanges();
    TestBed.tick();
    draw(402, 380, 430);
    addButton()!.click();
    expect(context.plan.addPlan.mock.calls[0][2][0]).toEqual(expect.objectContaining({
      orderType: 'LIMIT', limitPrice: 380, timeInForce: 'GTC'
    }));
  });

  it('sells every share held by default', () => {
    draw(402, 380);
    expect(text('draw-sentence')).toBe('Sell 4 MSFT now at about $402.00, dodging a $88.00 drop to $380.00.');
    addButton()!.click();
    expect(context.plan.addPlan).toHaveBeenCalledWith('draw', 'a1', [{ symbol: 'MSFT', side: 'SELL', quantity: 4 }]);
  });

  it('won\'t sell a stock the account doesn\'t hold', () => {
    tool.pick('NVDA');
    context.prices.set({ NVDA: 100 });
    TestBed.tick();
    fixture.detectChanges();
    draw(100, 90);
    expect(text('draw-sentence')).toContain("You'd need to own NVDA");
    expect(addButton()).toBeNull();
  });
});
