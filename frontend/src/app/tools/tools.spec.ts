import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Location } from '@angular/common';
import { provideLocationMocks } from '@angular/common/testing';
import { Router, provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { Subject, of, throwError } from 'rxjs';
import { BalanceService } from '../services/balance';
import { ClientHoldings, HoldingsService } from '../services/holdings';
import { MarketDataService } from '../services/market-data';
import { PriceStreamService } from '../services/price-stream';
import { createSpyObj } from '../../testing/create-spy-obj';
import { ToolsComponent } from './tools';
import { TOOL_ROUTES } from './tools.routes';
import { MiniDonutComponent } from './previews/mini-donut';
import { By } from '@angular/platform-browser';
import { TRADE_PLAN_KEY, TradePlanService } from './trade-plan';

describe('Trading Tools', () => {
  const position = (symbol: string, quantity: number) =>
    ({ instrumentId: symbol, symbol, instrumentName: symbol, assetClass: 'EQUITY', quantity, averageCost: 100 });
  const twoAccounts: ClientHoldings = {
    accounts: [
      { accountId: 'a1', accountNumber: 'ACC-0001', baseCurrency: 'USD', positions: [position('AAPL', 10)] },
      { accountId: 'a2', accountNumber: 'ACC-0002', baseCurrency: 'USD', positions: [position('MSFT', 2)] }
    ]
  };
  const balances = (ids: string[]) => ({
    accounts: ids.map((id, i) => ({ accountId: id, accountNumber: `ACC-000${i + 1}`, currency: 'USD', balance: 1000 * (i + 1) })),
    totalsByCurrency: {}
  });

  let holdingsApi: ReturnType<typeof createSpyObj<HoldingsService>>;
  let balanceApi: ReturnType<typeof createSpyObj<BalanceService>>;
  let stream: { prices: ReturnType<typeof signal<Record<string, number>>>; watch: ReturnType<typeof vi.fn> };
  let harness: RouterTestingHarness;
  let router: Router;

  const el = (): HTMLElement => harness.routeNativeElement!.closest('app-tools') ?? harness.fixture.nativeElement;
  const tiles = (): HTMLAnchorElement[] => Array.from(el().querySelectorAll<HTMLAnchorElement>('a.tile'));
  const tile = (tool: string) => el().querySelector<HTMLAnchorElement>(`a.tile[data-tool="${tool}"]`)!;

  async function open(url: string): Promise<void> {
    harness = await RouterTestingHarness.create();
    router = TestBed.inject(Router);
    await harness.navigateByUrl(url);
    await harness.fixture.whenStable();
    harness.detectChanges();
  }

  afterEach(() => sessionStorage.clear());

  // Chromium has view transitions; most specs drive plain navigation, and the two that don't stub it.
  beforeEach(() => Object.defineProperty(document, 'startViewTransition', { value: undefined, configurable: true, writable: true }));
  afterEach(() => { delete (document as unknown as Record<string, unknown>)['startViewTransition']; });

  beforeEach(() => {
    holdingsApi = createSpyObj('HoldingsService', ['getHoldings', 'getAccounts']);
    balanceApi = createSpyObj('BalanceService', ['getBalance']);
    const marketApi = createSpyObj('MarketDataService',
      ['getAllLatestPrices', 'getPreviousClose', 'getIntradayCloses', 'getQuote']);
    // Every quote sits 10 cents either side of its last trade.
    marketApi.getQuote.mockImplementation((symbol: string) =>
      of({ symbol, lastPrice: 100, askPrice: 100.1, bidPrice: 99.9, quoteTimestamp: '' }));
    sessionStorage.clear();
    stream = { prices: signal<Record<string, number>>({}), watch: vi.fn() };
    holdingsApi.getHoldings.mockReturnValue(of(twoAccounts));
    holdingsApi.getAccounts.mockReturnValue(of([]));
    balanceApi.getBalance.mockReturnValue(of(balances(['a1', 'a2'])));
    marketApi.getAllLatestPrices.mockReturnValue(of([
      { symbol: 'AAPL', name: 'Apple', price: 150, asOf: '' },
      { symbol: 'MSFT', name: 'Microsoft', price: 400, asOf: '' }
    ]));
    marketApi.getPreviousClose.mockReturnValue(of(140));
    marketApi.getIntradayCloses.mockReturnValue(of([145, 148, 150]));

    TestBed.configureTestingModule({
      providers: [
        provideRouter([{ path: 'tools', component: ToolsComponent, children: TOOL_ROUTES }]),
        provideLocationMocks(),
        { provide: HoldingsService, useValue: holdingsApi },
        { provide: BalanceService, useValue: balanceApi },
        { provide: MarketDataService, useValue: marketApi },
        { provide: PriceStreamService, useValue: stream }
      ]
    });
  });

  describe('hub', () => {
    it('shows one tile per tool, in order', async () => {
      await open('/tools');
      expect(tiles().map(t => t.querySelector('.tile-name')!.textContent!.trim()))
        .toEqual(['Rebalance Donut', 'Raise Cash', 'Basket Builder', 'Draw Your Trade']);
    });

    it('previews the selected account: allocation donut, cash, empty basket and a chart', async () => {
      await open('/tools');
      const donut = tile('rebalance').querySelector('app-mini-donut svg')!;
      // a1: 10 AAPL at 150 plus 1000 cash.
      expect(donut.getAttribute('aria-label')).toBe('Allocation: AAPL 60%, Cash 40%');
      expect(tile('raise-cash').querySelector('[data-testid="preview-cash"]')!.textContent).toContain('1,000.00');
      expect(tile('basket').querySelector('[data-testid="preview-basket"]')).not.toBeNull();
      expect(tile('draw').querySelector('app-sparkline svg path')).not.toBeNull();
    });

    it('keeps the held symbols on the live price stream', async () => {
      await open('/tools');
      expect(stream.watch).toHaveBeenCalledWith(['AAPL', 'MSFT']);
    });

    it('redraws previews from live prices without reloading', async () => {
      await open('/tools');
      stream.prices.set({ AAPL: 300 });
      harness.detectChanges();
      // 10 x 300 = 3000 against 1000 cash.
      expect(tile('rebalance').querySelector('app-mini-donut svg')!.getAttribute('aria-label'))
        .toBe('Allocation: AAPL 75%, Cash 25%');
      expect(holdingsApi.getHoldings).toHaveBeenCalledTimes(1);
    });

    it('shows skeletons while the account loads, except the basket which needs no data', async () => {
      holdingsApi.getHoldings.mockReturnValue(new Subject<ClientHoldings>());
      await open('/tools');
      for (const tool of ['rebalance', 'raise-cash', 'draw']) {
        expect(tile(tool).querySelector('[data-testid="preview-skeleton"]'), tool).not.toBeNull();
      }
      expect(tile('basket').querySelector('[data-testid="preview-skeleton"]')).toBeNull();
    });

    it('falls back to static icons when loading fails, and the tiles still open', async () => {
      balanceApi.getBalance.mockReturnValue(throwError(() => ({ error: { message: 'Down' } })));
      await open('/tools');
      for (const tool of ['rebalance', 'raise-cash', 'draw']) {
        expect(tile(tool).querySelector('[data-testid="preview-icon"]'), tool).not.toBeNull();
      }
      expect(el().querySelector('[role="alert"]')!.textContent).toContain('Down');

      tile('raise-cash').click();
      await harness.fixture.whenStable();
      expect(router.url).toBe('/tools/raise-cash');
    });
  });

  describe('navigation', () => {
    it('opens a tool from its tile on the same account, and Back returns to the hub', async () => {
      await open('/tools?account=a2');
      tile('rebalance').click();
      await harness.fixture.whenStable();
      harness.detectChanges();
      expect(router.url).toBe('/tools/rebalance?account=a2');
      expect(el().querySelector('h1')!.textContent).toBe('Rebalance Donut');

      // The harness skips the app's initial navigation, which is what starts the router on Back.
      router.setUpLocationChangeListener();
      const location = TestBed.inject(Location);
      location.back();
      await new Promise(resolve => setTimeout(resolve));
      await harness.fixture.whenStable();
      expect(router.url).toBe('/tools?account=a2');
    });

    for (const key of ['Enter', ' ']) {
      it(`opens a focused tile with ${key === ' ' ? 'Space' : key}`, async () => {
        await open('/tools');
        const target = tile('draw');
        target.focus();
        expect(document.activeElement).toBe(target);
        // Browsers turn Enter on a link into a click; Space is the tile's own handler.
        if (key === 'Enter') {
          target.click();
        } else {
          target.dispatchEvent(new KeyboardEvent('keydown', { key, bubbles: true, cancelable: true }));
        }
        await harness.fixture.whenStable();
        expect(router.url).toBe('/tools/draw?account=a1');
      });
    }

    it('leaves a modified click to the browser, so the link can open in a new tab', async () => {
      await open('/tools');
      const navigate = vi.spyOn(router, 'navigateByUrl');
      // Stand in for the browser, without letting the test runner actually open a tab.
      const swallow = (event: Event) => event.preventDefault();
      document.addEventListener('click', swallow);
      try {
        tile('basket').dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true, metaKey: true }));
      } finally {
        document.removeEventListener('click', swallow);
      }
      expect(navigate).not.toHaveBeenCalled();
    });

    it('links each tile to its deep-linkable URL', async () => {
      await open('/tools');
      expect(tiles().map(t => t.getAttribute('href'))).toEqual([
        '/tools/rebalance?account=a1', '/tools/raise-cash?account=a1',
        '/tools/basket?account=a1', '/tools/draw?account=a1'
      ]);
    });

    it('opens a tool directly from its URL', async () => {
      await open('/tools/basket');
      expect(el().querySelector('h1')!.textContent).toBe('Basket Builder');
    });

    it('sends an unknown tool back to the hub', async () => {
      await open('/tools/nope');
      expect(router.url.split('?')[0]).toBe('/tools');
    });

    it('returns to the hub from the tool page link', async () => {
      await open('/tools/draw?account=a2');
      el().querySelector<HTMLAnchorElement>('a.back')!.click();
      await harness.fixture.whenStable();
      expect(router.url).toBe('/tools?account=a2');
    });

    it('skips the expand transition under reduced motion', async () => {
      const startViewTransition = vi.fn();
      Object.assign(document, { startViewTransition });
      vi.spyOn(window, 'matchMedia').mockReturnValue({ matches: true } as MediaQueryList);
      try {
        await open('/tools');
        tile('rebalance').click();
        await harness.fixture.whenStable();
        expect(startViewTransition).not.toHaveBeenCalled();
        expect(router.url).toBe('/tools/rebalance?account=a1');
      } finally {
        harness.fixture.destroy();
      }
    });

    it('grows the tile into the tool when motion is allowed', async () => {
      const startViewTransition = vi.fn((update: () => Promise<void>) => { void update(); });
      Object.assign(document, { startViewTransition });
      vi.spyOn(window, 'matchMedia').mockReturnValue({ matches: false } as MediaQueryList);
      try {
        await open('/tools');
        const target = tile('raise-cash');
        target.click();
        expect(target.style.getPropertyValue('view-transition-name')).toBe('tool-expand');
        await harness.fixture.whenStable();
        expect(startViewTransition).toHaveBeenCalledTimes(1);
        expect(router.url).toBe('/tools/raise-cash?account=a1');
      } finally {
        harness.fixture.destroy();
      }
    });
  });

  describe('account context', () => {
    it('names the first account in the URL when none is given', async () => {
      await open('/tools');
      expect(router.url).toBe('/tools?account=a1');
    });

    it('acts on the account in the URL', async () => {
      await open('/tools?account=a2');
      // a2: 2 MSFT at 400 plus 2000 cash.
      expect(tile('raise-cash').querySelector('[data-testid="preview-cash"]')!.textContent).toContain('2,000.00');
    });

    it('replaces an account that is not the client\'s', async () => {
      await open('/tools/rebalance?account=someone-elses');
      expect(router.url).toBe('/tools/rebalance?account=a1');
    });

    it('switches account without leaving the current tool', async () => {
      await open('/tools/draw?account=a1');
      const tabs = Array.from(el().querySelectorAll<HTMLAnchorElement>('app-account-switcher a.tab'));
      expect(tabs.map(t => t.textContent!.trim())).toEqual(['Brokerage ··0001', 'Brokerage ··0002']);
      expect(tabs[1].getAttribute('href')).toBe('/tools/draw?account=a2');
      tabs[1].click();
      await harness.fixture.whenStable();
      expect(router.url).toBe('/tools/draw?account=a2');
    });

    it('hides the switcher for a client with one account', async () => {
      holdingsApi.getHoldings.mockReturnValue(of({ accounts: [twoAccounts.accounts[0]] }));
      balanceApi.getBalance.mockReturnValue(of(balances(['a1'])));
      await open('/tools');
      expect(el().querySelector('app-account-switcher')).toBeNull();
    });
  });

  describe('trade plan tray', () => {
    const plan = (): TradePlanService =>
      harness.fixture.debugElement.query(By.directive(ToolsComponent)).injector.get(TradePlanService);
    const q = <T extends Element = HTMLElement>(selector: string) => document.querySelector<T>(`app-tools ${selector}`)!;
    const qa = (selector: string) => Array.from(document.querySelectorAll<HTMLElement>(`app-tools ${selector}`));
    const text = (selector: string) => q(selector).textContent!.replace(/\s+/g, ' ').trim();
    const rows = () => qa('li.order').map(row => Array.from(row.querySelectorAll<HTMLElement>('.side, .symbol, .value, .source'))
      .map(cell => cell.textContent!.trim()).concat(row.querySelector<HTMLInputElement>('input')!.value).join(' '));
    const expand = () => { q<HTMLButtonElement>('[data-testid="tray-toggle"]').click(); harness.detectChanges(); };
    const add = (...args: Parameters<TradePlanService['addPlan']>) => { plan().addPlan(...args); harness.detectChanges(); };

    // a1 holds 10 AAPL (at 150) and $1,000 cash; quotes are 10 cents either side.
    const sellAndBuy = () => add('rebalance', 'a1', [
      { symbol: 'AAPL', side: 'SELL', quantity: 2 }, { symbol: 'MSFT', side: 'BUY', quantity: 1 }
    ]);

    it('docks collapsed on every tools page, saying when it is empty', async () => {
      await open('/tools/draw');
      expect(text('[data-testid="tray-summary"]')).toBe('Trade plan · no orders yet');
      expect(q('[data-testid="tray-toggle"]').getAttribute('aria-expanded')).toBe('false');
      expect(document.querySelector('app-tools [data-testid="tray-panel"]')).toBeNull();
    });

    it('shows the count and net cash impact collapsed, and announces adds', async () => {
      await open('/tools');
      sellAndBuy();
      // +2 x 149.90 - 400.10
      expect(text('[data-testid="tray-summary"]')).toBe('2 orders · −$100.30 cash');
      expect(text('.badge')).toBe('2');
      expect(text('[data-testid="tray-announcer"]')).toBe('2 orders from Rebalance added to your plan.');
    });

    it('lists each order and projects cash and allocation after them all', async () => {
      await open('/tools');
      sellAndBuy();
      expand();
      expect(q('[data-testid="tray-toggle"]').getAttribute('aria-expanded')).toBe('true');
      expect(rows()).toEqual(['Sell AAPL $299.80 Rebalance 2', 'Buy MSFT $400.10 Rebalance 1']);
      // 8 AAPL at 150, 1 MSFT at 400 and $899.70 cash.
      expect(qa('[data-testid="tray-projection"] dl div').map(d => `${d.querySelector('dt')!.textContent} ${d.querySelector('dd')!.textContent}`))
        .toEqual(['Cash after $899.70', 'AAPL 48%', 'MSFT 16%', 'Cash 36%']);
      expect(q('[data-testid="tray-projection"] app-mini-donut svg').getAttribute('aria-label'))
        .toBe('Allocation: AAPL 48%, MSFT 16%, Cash 36%');
    });

    it('revalues orders with live prices', async () => {
      await open('/tools');
      sellAndBuy();
      expand();
      stream.prices.set({ MSFT: 500 });
      harness.detectChanges();
      expect(rows()[1]).toBe('Buy MSFT $500.10 Rebalance 1');
      expect(text('[data-testid="tray-summary"]')).toBe('2 orders · −$200.30 cash');
    });

    it('edits a quantity, refusing less than one share inline', async () => {
      await open('/tools');
      sellAndBuy();
      expand();
      const input = qa('li.order')[1].querySelector<HTMLInputElement>('input')!;
      input.value = '0';
      input.dispatchEvent(new Event('change'));
      harness.detectChanges();
      expect(text('li.order .error')).toBe('Enter a whole number of shares, 1 or more.');
      expect(input.getAttribute('aria-invalid')).toBe('true');
      expect(plan().orders().find(o => o.symbol === 'MSFT')!.quantity).toBe(1);

      // 4 x 400.10 is more than $1,000 cash plus $299.80 from the sale.
      input.value = '4';
      input.dispatchEvent(new Event('change'));
      harness.detectChanges();
      expect(document.querySelector('app-tools li.order .error')).toBeNull();
      expect(rows()[1]).toBe('Buy MSFT $1,600.40 Rebalance 4');
      expect(qa('[data-warning="not-enough-cash"]')).toHaveLength(1);
    });

    it('removes one order, or clears them all', async () => {
      await open('/tools');
      sellAndBuy();
      expand();
      qa('li.order')[0].querySelector<HTMLButtonElement>('button.remove')!.click();
      harness.detectChanges();
      expect(rows()).toEqual(['Buy MSFT $400.10 Rebalance 1']);

      qa('.panel-head button').find(b => b.textContent!.includes('Clear all'))!.click();
      harness.detectChanges();
      expect(qa('li.order')).toHaveLength(0);
      expect(text('[data-testid="tray-summary"]')).toBe('Trade plan · no orders yet');
    });

    it('flags opposite orders on one symbol and nets them', async () => {
      await open('/tools');
      add('basket', 'a1', [{ symbol: 'AAPL', side: 'BUY', quantity: 5 }]);
      add('raise-cash', 'a1', [{ symbol: 'AAPL', side: 'SELL', quantity: 3 }]);
      expand();
      expect(text('[data-conflict="AAPL"]')).toContain('The plan both buys and sells AAPL.');
      q<HTMLButtonElement>('[data-conflict="AAPL"] button').click();
      harness.detectChanges();
      expect(rows()).toEqual(['Buy AAPL $300.20 Netted 2']);
      expect(document.querySelector('app-tools [data-conflict]')).toBeNull();
    });

    it('warns about selling more than the account holds', async () => {
      await open('/tools');
      add('raise-cash', 'a1', [{ symbol: 'AAPL', side: 'SELL', quantity: 12 }]);
      expand();
      expect(text('[data-warning="oversell"]')).toBe('The plan sells 12 AAPL but the account holds 10.');
    });

    it('collapses on Escape and hands focus back to its toggle', async () => {
      await open('/tools');
      expand();
      q('[data-testid="tray-panel"]').dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
      harness.detectChanges();
      expect(document.querySelector('app-tools [data-testid="tray-panel"]')).toBeNull();
      expect(document.activeElement).toBe(q('[data-testid="tray-toggle"]'));
    });

    it('keeps the plan while moving between tools', async () => {
      await open('/tools');
      sellAndBuy();
      await router.navigateByUrl('/tools/rebalance?account=a1');
      await harness.fixture.whenStable();
      harness.detectChanges();
      expect(text('[data-testid="tray-summary"]')).toBe('2 orders · −$100.30 cash');
    });

    it('comes back after a refresh, on the plan\'s account', async () => {
      // What the tray saved before the page reloaded; the round trip itself is in trade-plan.spec.ts.
      sessionStorage.setItem(TRADE_PLAN_KEY, JSON.stringify({
        owner: '', accountId: 'a2', orders: [{ id: 'o1', source: 'basket', symbol: 'MSFT', side: 'BUY', quantity: 1 }]
      }));
      await open('/tools');
      expect(router.url).toBe('/tools?account=a2');
      expect(text('[data-testid="tray-summary"]')).toBe('1 order · −$400.10 cash');
    });

    it('drops a stored plan for an account that is not the client\'s', async () => {
      sessionStorage.setItem(TRADE_PLAN_KEY, JSON.stringify({
        owner: '', accountId: 'closed', orders: [{ id: 'o1', source: 'basket', symbol: 'AAPL', side: 'BUY', quantity: 1 }]
      }));
      await open('/tools');
      expect(plan().isEmpty()).toBe(true);
      expect(plan().accountId()).toBe('a1');
    });
  });

  describe('switching account with a plan', () => {
    const plan = (): TradePlanService =>
      harness.fixture.debugElement.query(By.directive(ToolsComponent)).injector.get(TradePlanService);
    const dialog = () => document.querySelector<HTMLElement>('app-tools [data-testid="switch-dialog"]');
    async function go(url: string): Promise<void> {
      await router.navigateByUrl(url);
      await harness.fixture.whenStable();
      harness.detectChanges();
    }

    it('switches freely while the plan is empty', async () => {
      await open('/tools?account=a1');
      await go('/tools?account=a2');
      expect(dialog()).toBeNull();
      expect(plan().accountId()).toBe('a2');
    });

    it('asks to stay or clear, and staying goes back with the plan', async () => {
      await open('/tools?account=a1');
      plan().addPlan('basket', 'a1', [{ symbol: 'AAPL', side: 'BUY', quantity: 1 }]);
      await go('/tools/draw?account=a2');
      expect(dialog()!.textContent).toContain('Your plan has 1 order for Brokerage ··0001');
      await new Promise(resolve => queueMicrotask(() => resolve(null)));
      expect(document.activeElement).toBe(dialog()!.querySelector('[data-testid="stay"]'));

      dialog()!.querySelector<HTMLButtonElement>('[data-testid="stay"]')!.click();
      await harness.fixture.whenStable();
      harness.detectChanges();
      expect(router.url).toBe('/tools/draw?account=a1');
      expect(dialog()).toBeNull();
      expect(plan().count()).toBe(1);
    });

    it('stays on Escape', async () => {
      await open('/tools?account=a1');
      plan().addPlan('basket', 'a1', [{ symbol: 'AAPL', side: 'BUY', quantity: 1 }]);
      await go('/tools?account=a2');
      dialog()!.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
      await harness.fixture.whenStable();
      expect(router.url).toBe('/tools?account=a1');
    });

    it('clears the plan and switches', async () => {
      await open('/tools?account=a1');
      plan().addPlan('basket', 'a1', [{ symbol: 'AAPL', side: 'BUY', quantity: 1 }]);
      await go('/tools?account=a2');
      dialog()!.querySelector<HTMLButtonElement>('[data-testid="clear-and-switch"]')!.click();
      await harness.fixture.whenStable();
      harness.detectChanges();
      expect(dialog()).toBeNull();
      expect(router.url).toBe('/tools?account=a2');
      expect(plan().isEmpty()).toBe(true);
      expect(plan().accountId()).toBe('a2');
    });
  });
});

describe('MiniDonutComponent', () => {
  it('lays slices end to end from 12 o\'clock with a gap between them', () => {
    const fixture = TestBed.createComponent(MiniDonutComponent);
    fixture.componentRef.setInput('slices', [
      { key: 'a', label: 'A', value: 3, share: 0.75, color: '#000' },
      { key: 'b', label: 'B', value: 1, share: 0.25, color: '#fff' }
    ]);
    fixture.detectChanges();
    const arcs = fixture.componentInstance.arcs;
    expect(arcs.map(a => a.offset)).toEqual([25, -50]);
    expect(arcs[0].dash).toBe('74.2 25.8');
  });

  it('describes an empty allocation', () => {
    const fixture = TestBed.createComponent(MiniDonutComponent);
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('svg').getAttribute('aria-label')).toBe('No allocation yet');
  });
});
