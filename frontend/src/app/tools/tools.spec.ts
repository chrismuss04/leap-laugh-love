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

  // Chromium has view transitions; most specs drive plain navigation, and the two that don't stub it.
  beforeEach(() => Object.defineProperty(document, 'startViewTransition', { value: undefined, configurable: true, writable: true }));
  afterEach(() => { delete (document as unknown as Record<string, unknown>)['startViewTransition']; });

  beforeEach(() => {
    holdingsApi = createSpyObj('HoldingsService', ['getHoldings', 'getAccounts']);
    balanceApi = createSpyObj('BalanceService', ['getBalance']);
    const marketApi = createSpyObj('MarketDataService', ['getAllLatestPrices', 'getPreviousClose', 'getIntradayCloses']);
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
