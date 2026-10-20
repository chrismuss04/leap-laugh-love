import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Location } from '@angular/common';
import { provideLocationMocks } from '@angular/common/testing';
import { Router, provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { Subject, of, throwError } from 'rxjs';
import { HttpErrorResponse } from '@angular/common/http';
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
import { OrderService, OrderStatus, OrderSubmissionRequest, OrderSubmissionResponse } from '../services/order';
import { MarketStatusService } from '../services/market-status';
import { ToolsComponent as ToolsPage } from './tools';
import { SKIPPED_MESSAGE, UNKNOWN_MESSAGE } from './plan-submission';
import { RebalanceToolComponent } from './rebalance/rebalance-tool';
import { InstrumentsService } from '../services/instruments';

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
  let orderApi: ReturnType<typeof createSpyObj<OrderService>>;
  let marketApi: ReturnType<typeof createSpyObj<MarketDataService>>;
  let session: ReturnType<typeof signal<string | null>>;
  /** A filled market order, as order-app answers one. */
  const filled = (request: OrderSubmissionRequest, status: OrderStatus = 'FILLED'): OrderSubmissionResponse => ({
    orderId: `order-${request.symbol}`, accountId: request.accountId, accountNumber: 'ACC-0001', symbol: request.symbol,
    side: request.side, quantity: request.quantity, status, submittedAt: '', filledAt: null, rejectionReason: null,
    execution: { executionId: 'x', fillQuantity: request.quantity, fillPrice: request.quotedPrice!, status: 'FILLED', executedAt: '', reason: null },
    accountBalanceAfter: null
  });
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
    marketApi = createSpyObj('MarketDataService',
      ['getAllLatestPrices', 'getPreviousClose', 'getIntradayCloses', 'getQuote', 'getHistory']);
    marketApi.getHistory.mockReturnValue(of([]));
    // Every quote sits 10 cents either side of its last trade.
    marketApi.getQuote.mockImplementation((symbol: string) =>
      of({ symbol, lastPrice: 100, askPrice: 100.1, bidPrice: 99.9, quoteTimestamp: '' }));
    sessionStorage.clear();
    orderApi = createSpyObj('OrderService', ['submitOrder', 'getOpenOrders']);
    orderApi.submitOrder.mockImplementation((request: OrderSubmissionRequest) => of(filled(request)));
    orderApi.getOpenOrders.mockReturnValue(of([]));
    session = signal<string | null>('REGULAR');
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
        { provide: PriceStreamService, useValue: stream },
        { provide: OrderService, useValue: orderApi },
        { provide: MarketStatusService, useValue: { session } },
        { provide: InstrumentsService, useValue: { getOverview: () => of({ period: 'today', periodStart: '', asOf: '', instruments: [] }) } }
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

  describe('rebalance donut', () => {
    const donut = () => el().querySelector('app-allocation-donut')!;
    const total = () => donut().querySelector('[data-testid="donut-total"]')?.textContent ?? '';
    /** Lets the donut measure itself and draw. */
    async function drawn(): Promise<void> {
      for (let i = 0; i < 3; i++) {
        await new Promise(resolve => requestAnimationFrame(resolve));
        harness.detectChanges();
      }
      await harness.fixture.whenStable();
    }

    it("draws the selected account's holdings and cash, with its total in the centre", async () => {
      await open('/tools/rebalance?account=a1');
      await drawn();
      // a1: 10 AAPL at 150 plus 1000 cash.
      expect(Array.from(donut().querySelectorAll('path.slice')).map(p => p.getAttribute('data-key'))).toEqual(['AAPL', 'cash']);
      expect(total()).toContain('$2,500.00');
    });

    it('resizes slices and the total from the live price stream', async () => {
      await open('/tools/rebalance?account=a1');
      await drawn();
      stream.prices.set({ AAPL: 300 });
      harness.detectChanges();
      await drawn();
      expect(total()).toContain('$4,000.00');
      const percents = Array.from(el().querySelectorAll('app-target-table tbody td.current-col')).map(p => p.textContent!.trim());
      expect(percents).toEqual(['75%', '25%']);
    });

    it('shows a skeleton ring while loading, and an error with Retry if loading fails', async () => {
      holdingsApi.getHoldings.mockReturnValue(throwError(() => new HttpErrorResponse({ status: 503 })));
      await open('/tools/rebalance?account=a1');
      expect(donut().querySelector('[data-testid="donut-error"]')).not.toBeNull();

      const pending = new Subject<ClientHoldings>();
      holdingsApi.getHoldings.mockReturnValue(pending);
      donut().querySelector<HTMLButtonElement>('[data-testid="donut-retry"]')!.click();
      harness.detectChanges();
      expect(donut().querySelector('[data-testid="donut-loading"]')).not.toBeNull();

      pending.next(twoAccounts);
      pending.complete();
      harness.detectChanges();
      await drawn();
      expect(donut().querySelector('path.slice[data-key="AAPL"]')).not.toBeNull();
    });

    it('lets the client set targets on the donut or in the table under it', async () => {
      await open('/tools/rebalance?account=a1');
      await drawn();
      // a1: AAPL $1,500 (60%) and cash $1,000 (40%), one boundary between them.
      expect(donut().querySelectorAll('.handle')).toHaveLength(1);
      const aapl = el().querySelector<HTMLInputElement>('input[data-target-input="AAPL"]')!;
      expect(aapl.value).toBe('60');

      aapl.value = '80';
      aapl.dispatchEvent(new Event('change'));
      harness.detectChanges();
      expect(el().querySelector<HTMLInputElement>('input[data-target-input="cash"]')!.value).toBe('20');
      expect(donut().querySelector('.handle')!.getAttribute('aria-valuenow')).toBe('80');
      // $2,000 of AAPL is 13.3 shares: 3 more, at the 150.10 ask.
      expect(el().querySelector('[data-change="AAPL"]')!.textContent).toContain('$450.30');
      expect(el().querySelector('[data-change="AAPL"]')!.textContent).toContain('Buy 3');
    });

    it('starts targets afresh on another account', async () => {
      await open('/tools/rebalance?account=a1');
      await drawn();
      const aapl = el().querySelector<HTMLInputElement>('input[data-target-input="AAPL"]')!;
      aapl.value = '80';
      aapl.dispatchEvent(new Event('change'));
      await harness.navigateByUrl('/tools/rebalance?account=a2');
      await drawn();
      // a2: 2 MSFT at 400 against 2,000 cash.
      expect(el().querySelector<HTMLInputElement>('input[data-target-input="MSFT"]')!.value).toBe('28.6');
      expect(el().querySelector<HTMLButtonElement>('[data-testid="reset-targets"]')!.disabled).toBe(true);
    });

    it('shows only cash and a prompt to add a holding for an account with no holdings', async () => {
      holdingsApi.getHoldings.mockReturnValue(of({
        accounts: [{ accountId: 'a1', accountNumber: 'ACC-0001', baseCurrency: 'USD', positions: [] }]
      }));
      balanceApi.getBalance.mockReturnValue(of(balances(['a1'])));
      await open('/tools/rebalance?account=a1');
      await drawn();
      expect(Array.from(donut().querySelectorAll('path.slice')).map(p => p.getAttribute('data-key'))).toEqual(['cash']);
      expect(donut().querySelector('[data-testid="donut-empty"]')!.textContent).toContain('Add a holding');
      // Nothing to reshape yet, so no target ring or table.
      expect(donut().querySelector('.handle')).toBeNull();
      expect(el().querySelector('app-target-table')).toBeNull();
    });
  });

  describe('rebalance trades', () => {
    const plan = (): TradePlanService =>
      harness.fixture.debugElement.query(By.directive(ToolsComponent)).injector.get(TradePlanService);
    const panel = () => el().querySelector('[data-testid="trade-preview"]')!;
    const trades = () => Array.from(panel().querySelectorAll<HTMLElement>('.trades li')).map(li => li.innerText.replace(/\s+/g, ' ').trim());
    const addButton = () => panel().querySelector<HTMLButtonElement>('[data-testid="add-to-plan"]')!;
    const typeTarget = (key: string, value: string) => {
      const input = el().querySelector<HTMLInputElement>(`input[data-target-input="${key}"]`)!;
      input.value = value;
      input.dispatchEvent(new Event('change'));
      harness.detectChanges();
    };
    async function drawn(): Promise<void> {
      for (let i = 0; i < 3; i++) {
        await new Promise(resolve => requestAnimationFrame(resolve));
        harness.detectChanges();
      }
      await harness.fixture.whenStable();
    }

    beforeEach(async () => {
      await open('/tools/rebalance?account=a1');
      await drawn();
    });

    it("starts 'Already balanced' with nothing to add", () => {
      expect(panel().querySelector('[data-testid="already-balanced"]')!.textContent).toContain('Already balanced');
      expect(panel().querySelector('[data-testid="drift"]')!.textContent).toContain('0%');
      expect(addButton().disabled).toBe(true);
    });

    it('prices trades at the ask and bid, asking for every held quote', () => {
      expect(marketApi.getQuote).toHaveBeenCalledWith('AAPL');
      // a1: 10 AAPL at 150 and 1,000 cash. 30% of 2,500 is 5 shares; they sell at the 149.90 bid.
      typeTarget('AAPL', '30');
      expect(trades()).toEqual(['↓ Sell 5 AAPL ≈ $750']);
      expect(panel().querySelector('[data-testid="cash-after"]')!.textContent).toContain('$1,750');
    });

    it('updates the list as targets change, straight after each drag step', async () => {
      typeTarget('AAPL', '80');
      expect(trades()).toEqual(['↑ Buy 3 AAPL ≈ $450']);

      // One drag step to 35%, with no time allowed to pass before checking. (Not 30%: exactly half a
      // turn from 80% is a tie between the two ways round, which float noise would decide.)
      const donut = el().querySelector('app-allocation-donut')!;
      const svg = donut.querySelector('svg')!;
      const knob = donut.querySelector('.handle')!;
      const box = svg.getBoundingClientRect();
      const width = box.width;
      const height = box.height;
      const at = (tenths: number) => {
        const angle = (tenths / 1000) * Math.PI * 2;
        return { clientX: box.left + width / 2 + Math.sin(angle) * 100, clientY: box.top + height / 2 - Math.cos(angle) * 100 };
      };
      knob.dispatchEvent(new PointerEvent('pointerdown', { bubbles: true, pointerId: 1, pointerType: 'mouse', ...at(800) }));
      svg.dispatchEvent(new PointerEvent('pointermove', { bubbles: true, pointerId: 1, pointerType: 'mouse', ...at(350) }));
      harness.detectChanges();
      // 35% of 2,500 is 5.8 shares: sell 4 of the 10, at the 149.90 bid.
      expect(trades()).toEqual(['↓ Sell 4 AAPL ≈ $600']);
      svg.dispatchEvent(new PointerEvent('pointerup', { bubbles: true, pointerId: 1, pointerType: 'mouse', ...at(350) }));
    });

    it("shows each slice's trade as a badge beside its label", async () => {
      // Phones leave badges to the table, so give the donut a desktop's width.
      (harness.fixture.nativeElement as HTMLElement).style.width = '1100px';
      typeTarget('AAPL', '30');
      await drawn();
      const badge = el().querySelector('[data-badge="AAPL"]')!;
      expect(badge.textContent!.trim()).toBe('↓5');
      expect(badge.classList).toContain('sell');
    });

    it('goes back to Already balanced when targets come within 1%', () => {
      typeTarget('AAPL', '30');
      typeTarget('AAPL', '59.5');
      expect(panel().querySelector('[data-testid="already-balanced"]')).not.toBeNull();
      expect(addButton().disabled).toBe(true);
    });

    it("shows the engine's warnings inline", async () => {
      // a2: 2 MSFT at 400 and 2,000 cash. All in MSFT is 5 more shares at the 400.10 ask: $2,000.50,
      // more than the cash, so the buy is scaled down.
      await harness.navigateByUrl('/tools/rebalance?account=a2');
      await drawn();
      typeTarget('MSFT', '100');
      const warning = panel().querySelector('[data-warning="not-enough-cash"]')!;
      expect(warning.textContent).toContain('scaled down');
      expect(warning.textContent).toContain('at least 0.5% cash');
    });

    it('adds the trades to the tray tagged Rebalance', () => {
      typeTarget('AAPL', '30');
      expect(addButton().textContent).toContain('Add to plan');
      addButton().click();
      harness.detectChanges();
      expect(plan().orders().map(o => [o.source, o.side, o.symbol, o.quantity])).toEqual([['rebalance', 'SELL', 'AAPL', 5]]);
      expect(addButton().textContent).toContain('In your plan');
      expect(addButton().disabled).toBe(true);
    });

    it("replaces earlier Rebalance trades, and leaves other tools' orders alone", () => {
      plan().addPlan('basket', 'a1', [{ symbol: 'MSFT', side: 'BUY', quantity: 1 }]);
      typeTarget('AAPL', '30');
      addButton().click();
      typeTarget('AAPL', '80');
      harness.detectChanges();
      expect(addButton().textContent).toContain('Update plan');
      expect(panel().textContent).toContain('Replaces the Rebalance trades');
      addButton().click();
      harness.detectChanges();
      expect(plan().orders().map(o => [o.source, o.side, o.symbol, o.quantity])).toEqual([
        ['basket', 'BUY', 'MSFT', 1], ['rebalance', 'BUY', 'AAPL', 3]
      ]);
    });
  });

  describe('rebalance: adding a stock', () => {
    const plan = (): TradePlanService =>
      harness.fixture.debugElement.query(By.directive(ToolsComponent)).injector.get(TradePlanService);
    const q = <T extends Element = HTMLElement>(selector: string) => el().querySelector<T>(selector);
    const target = (key: string) => q<HTMLInputElement>(`input[data-target-input="${key}"]`)?.value ?? null;
    const trades = () => Array.from(el().querySelectorAll<HTMLElement>('[data-testid="trade-preview"] .trades li'))
      .map(li => li.innerText.replace(/\s+/g, ' ').trim());
    async function drawn(): Promise<void> {
      for (let i = 0; i < 3; i++) {
        await new Promise(resolve => requestAnimationFrame(resolve));
        harness.detectChanges();
      }
      await harness.fixture.whenStable();
    }
    /** Opens Add stock and types into its search. */
    async function search(text: string): Promise<void> {
      if (!q('[data-testid="add-stock-panel"]')) {
        q<HTMLButtonElement>('[data-testid="add-stock"]')!.click();
        harness.detectChanges();
        await new Promise(resolve => setTimeout(resolve));
      }
      const input = q<HTMLInputElement>('[data-testid="add-stock-panel"] input')!;
      input.focus();
      input.value = text;
      input.dispatchEvent(new Event('input'));
      harness.detectChanges();
      await harness.fixture.whenStable();
      harness.detectChanges();
    }
    const result = (symbol: string) => q<HTMLLIElement>(`[data-testid="add-stock-panel"] li[data-symbol="${symbol}"]`)!;
    async function add(symbol: string): Promise<void> {
      await search(symbol);
      result(symbol).click();
      harness.detectChanges();
      await drawn();
    }

    beforeEach(async () => {
      marketApi.getAllLatestPrices.mockReturnValue(of([
        { symbol: 'AAPL', name: 'Apple', price: 150, asOf: '' },
        { symbol: 'MSFT', name: 'Microsoft', price: 400, asOf: '' },
        { symbol: 'NVDA', name: 'NVIDIA', price: 100, asOf: '' },
        { symbol: 'SPX', name: 'S&P 500', price: 5000, asOf: '' }
      ]));
      await open('/tools/rebalance?account=a1');
      await drawn();
    });

    it('opens the symbol search, focused, from an Add stock button', async () => {
      q<HTMLButtonElement>('[data-testid="add-stock"]')!.click();
      harness.detectChanges();
      await new Promise(resolve => setTimeout(resolve));
      expect(q('[data-testid="add-stock-panel"]')).not.toBeNull();
      expect(document.activeElement).toBe(q('[data-testid="add-stock-panel"] input'));
      expect(q('[data-testid="add-stock"]')!.getAttribute('aria-expanded')).toBe('true');
    });

    it('adds a chosen stock as a new slice with a 5% target taken evenly from the others', async () => {
      // a1: AAPL 60% and cash 40%; each gives 2.5%.
      await add('NVDA');
      expect([target('AAPL'), target('NVDA'), target('cash')]).toEqual(['57.5', '5', '37.5']);
      expect(q('[data-new="NVDA"]')).not.toBeNull();
      expect(q('[data-testid="add-stock-panel"]')).toBeNull();
      expect(document.activeElement).toBe(q('[data-testid="add-stock"]'));
    });

    it('shows the BUY for the added stock in the preview, priced live at its ask', async () => {
      await add('NVDA');
      // 5% of $2,500 is 1.25 shares of NVDA: 1 whole share, at the 100.10 ask.
      expect(trades()).toEqual(['↑ Buy 1 NVDA ≈ $100']);
      expect(marketApi.getQuote).toHaveBeenCalledWith('NVDA');
      expect(stream.watch).toHaveBeenLastCalledWith(expect.arrayContaining(['AAPL', 'NVDA']));
    });

    it('only lets tradable stocks be added', async () => {
      await search('SP');
      expect(result('SPX').textContent).toContain('Not tradable');
      result('SPX').click();
      harness.detectChanges();
      expect(target('SPX')).toBeNull();
    });

    it("doesn't add a stock that's already in the donut", async () => {
      await search('AAPL');
      expect(result('AAPL').textContent).toContain('In donut');
      result('AAPL').click();
      harness.detectChanges();
      expect(el().querySelectorAll('input[data-target-input="AAPL"]')).toHaveLength(1);

      await add('NVDA');
      await search('NVDA');
      expect(result('NVDA').textContent).toContain('In donut');
    });

    it('takes the new target from unlocked slices only', async () => {
      q<HTMLButtonElement>('tr[data-key="AAPL"] button.lock')!.click();
      harness.detectChanges();
      await add('NVDA');
      expect([target('AAPL'), target('NVDA'), target('cash')]).toEqual(['60', '5', '35']);
    });

    it('removes an added stock again, giving its target back', async () => {
      await add('NVDA');
      q<HTMLButtonElement>('[data-remove="NVDA"]')!.click();
      harness.detectChanges();
      await drawn();
      expect(target('NVDA')).toBeNull();
      expect([target('AAPL'), target('cash')]).toEqual(['60', '40']);
      expect(q('[data-testid="already-balanced"]')).not.toBeNull();
      // Only added stocks can be removed.
      expect(q('[data-remove="AAPL"]')).toBeNull();
    });

    it('puts the added stock\'s BUY in the plan', async () => {
      await add('NVDA');
      q<HTMLButtonElement>('[data-testid="add-to-plan"]')!.click();
      harness.detectChanges();
      expect(plan().orders().map(o => [o.source, o.side, o.symbol, o.quantity])).toEqual([['rebalance', 'BUY', 'NVDA', 1]]);
    });

    it('closes the search with Escape, the button again, or a click elsewhere', async () => {
      await search('NV');
      const panel = () => q('[data-testid="add-stock-panel"]');
      panel()!.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
      harness.detectChanges();
      expect(panel()).toBeNull();
      expect(document.activeElement).toBe(q('[data-testid="add-stock"]'));

      await search('NV');
      q<HTMLButtonElement>('[data-testid="add-stock"]')!.click();
      harness.detectChanges();
      expect(panel()).toBeNull();

      await search('NV');
      panel()!.dispatchEvent(new PointerEvent('pointerdown', { bubbles: true }));
      harness.detectChanges();
      expect(panel()).not.toBeNull();
      document.body.dispatchEvent(new PointerEvent('pointerdown', { bubbles: true }));
      harness.detectChanges();
      expect(panel()).toBeNull();
    });

    it("won't add a stock it can't, or put nothing in the plan", async () => {
      const tool = harness.fixture.debugElement.query(By.directive(RebalanceToolComponent)).componentInstance as RebalanceToolComponent;
      tool.addStock('SPX', 'S&P 500');
      tool.addStock('AAPL', null);
      expect(tool.targets.keys()).toEqual(['AAPL', 'cash']);
      tool.addToPlan();
      expect(plan().orders()).toEqual([]);

      tool.addStock('NVDA', null);
      expect(tool.targets.added()).toEqual([{ symbol: 'NVDA', name: 'NVDA' }]);
    });

    it('drops added stocks on another account', async () => {
      await add('NVDA');
      await harness.navigateByUrl('/tools/rebalance?account=a2');
      await drawn();
      expect(target('NVDA')).toBeNull();
      expect(target('MSFT')).not.toBeNull();
    });
  });

  it("marks a held stock that isn't priced yet as Owned in the search", async () => {
    holdingsApi.getHoldings.mockReturnValue(of({
      accounts: [{ accountId: 'a1', accountNumber: 'ACC-0001', baseCurrency: 'USD', positions: [position('AAPL', 10), position('ZZZ', 1)] }]
    }));
    balanceApi.getBalance.mockReturnValue(of(balances(['a1'])));
    await open('/tools/rebalance?account=a1');
    const tool = harness.fixture.debugElement.query(By.directive(RebalanceToolComponent)).componentInstance as RebalanceToolComponent;
    expect(tool.unavailable('ZZZ')).toBe('Owned');
    expect(tool.unavailable('AAPL')).toBe('In donut');
    expect(tool.unavailable('NVDA')).toBeNull();
  });

  it("opens Add stock from an empty account's prompt, funding the stock from cash", async () => {
    holdingsApi.getHoldings.mockReturnValue(of({
      accounts: [{ accountId: 'a1', accountNumber: 'ACC-0001', baseCurrency: 'USD', positions: [] }]
    }));
    balanceApi.getBalance.mockReturnValue(of(balances(['a1'])));
    await open('/tools/rebalance?account=a1');
    for (let i = 0; i < 3; i++) {
      await new Promise(resolve => requestAnimationFrame(resolve));
      harness.detectChanges();
    }
    el().querySelector<HTMLButtonElement>('[data-testid="add-holding"]')!.click();
    harness.detectChanges();
    await new Promise(resolve => setTimeout(resolve));
    const input = el().querySelector<HTMLInputElement>('[data-testid="add-stock-panel"] input')!;
    expect(document.activeElement).toBe(input);
    input.focus();
    input.value = 'MSFT';
    input.dispatchEvent(new Event('input'));
    harness.detectChanges();
    el().querySelector<HTMLLIElement>('[data-testid="add-stock-panel"] li[data-symbol="MSFT"]')!.click();
    harness.detectChanges();
    expect(el().querySelector<HTMLInputElement>('input[data-target-input="MSFT"]')!.value).toBe('5');
    expect(el().querySelector<HTMLInputElement>('input[data-target-input="cash"]')!.value).toBe('95');
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

  describe('reviewing and submitting the plan', () => {
    const plan = (): TradePlanService =>
      harness.fixture.debugElement.query(By.directive(ToolsComponent)).injector.get(TradePlanService);
    const q = <T extends Element = HTMLElement>(selector: string) => document.querySelector<T>(`app-tools ${selector}`);
    const sheet = () => q('[data-testid="review-sheet"]');
    /** Each match's text, its child elements' texts joined by spaces. */
    const cells = (selector: string) => Array.from(document.querySelectorAll<HTMLElement>(`app-tools ${selector}`))
      .map(el => (el.children.length ? Array.from(el.children).map(c => c.textContent!.trim()).join(' ') : el.textContent!)
        .replace(/\s+/g, ' ').trim());
    const submitButton = () => q<HTMLButtonElement>('[data-testid="submit"]')!;
    async function settle(): Promise<void> {
      for (let i = 0; i < 5; i++) {
        await new Promise(resolve => setTimeout(resolve));
      }
      harness.detectChanges();
    }
    async function review(...plans: Parameters<TradePlanService['addPlan']>[]): Promise<void> {
      for (const args of plans) {
        plan().addPlan(...args);
      }
      harness.detectChanges();
      q<HTMLButtonElement>('[data-testid="tray-toggle"]')!.click();
      harness.detectChanges();
      q<HTMLButtonElement>('[data-testid="review-plan"]')!.click();
      harness.detectChanges();
    }
    // a1 holds 10 AAPL (at 150) and $1,000 cash; quotes are 10 cents either side.
    const sellAndBuy: Parameters<TradePlanService['addPlan']> = ['rebalance', 'a1', [
      { symbol: 'AAPL', side: 'SELL', quantity: 2 }, { symbol: 'MSFT', side: 'BUY', quantity: 1 }
    ]];

    it('lists every order at live prices, with the totals', async () => {
      await open('/tools');
      await review(sellAndBuy);
      expect(cells('[data-testid="review-sheet"] tbody tr')).toEqual([
        'Sell AAPL 2 $149.90 $299.80', 'Buy MSFT 1 $400.10 $400.10'
      ]);
      expect(cells('[data-testid="review-totals"] div')).toEqual([
        'Cash spent $400.10', 'Cash raised $299.80', 'Net cash change −$100.30', 'Cash after $899.70'
      ]);
      expect(submitButton().disabled).toBe(false);
      expect(submitButton().textContent!.trim()).toBe('Submit 2 orders');
      expect(document.activeElement?.textContent?.trim()).toBe('Cancel');
    });

    it('shows the account\'s saved price protection, which the server applies to every order', async () => {
      holdingsApi.getAccounts.mockReturnValue(of([{ accountId: 'a1', accountNumber: 'ACC-0001', status: 'ACTIVE',
        baseCurrency: 'USD', tradingEnabled: true, maxSlippagePercent: 1, createdAt: '', inactiveSince: null }]));
      await open('/tools');
      await review(sellAndBuy);
      expect(cells('[data-testid="review-protection"] strong')).toEqual(['1%']);
    });

    it('says when price protection is off', async () => {
      await open('/tools');
      await review(sellAndBuy);
      expect(document.querySelector('app-tools [data-testid="review-protection"]')!.textContent!.replace(/\s+/g, ' ')).toContain('Price protection: Off');
    });

    it('warns about a holding the plan buys past the concentration threshold, not one it leaves alone', async () => {
      await open('/tools');
      // MSFT would be $1,200 of $2,500; AAPL is already 60% but isn't bought.
      await review(['basket', 'a1', [{ symbol: 'MSFT', side: 'BUY', quantity: 3 }]]);
      expect(cells('[data-testid="review-sheet"] [data-warning="concentration"]')).toEqual(['MSFT would be 48% of your portfolio.']);
      expect(cells('[data-testid="review-sheet"] [data-warning="not-enough-cash"]')).toHaveLength(1);
    });

    it('explains what changes outside regular hours', async () => {
      session.set('AFTER_HOURS');
      await open('/tools');
      await review(sellAndBuy);
      expect(cells('[data-testid="session-notice"]')[0]).toContain('will wait for the next open');
    });

    it('can\'t be submitted while prices load', async () => {
      marketApi.getQuote.mockReturnValue(new Subject());
      await open('/tools');
      await review(sellAndBuy);
      expect(submitButton().disabled).toBe(true);
      expect(cells('[data-testid="blocker"]')).toEqual(['Loading prices…']);
    });

    it('can\'t be submitted while it sells shares the account doesn\'t hold', async () => {
      await open('/tools');
      await review(['raise-cash', 'a1', [{ symbol: 'AAPL', side: 'SELL', quantity: 11 }]]);
      expect(submitButton().disabled).toBe(true);
      expect(cells('[data-testid="blocker"]')).toEqual(['The plan sells more shares than the account holds.']);
    });

    it('can\'t be submitted once the plan is empty', async () => {
      await open('/tools');
      await review(sellAndBuy);
      plan().clear();
      harness.detectChanges();
      expect(submitButton().disabled).toBe(true);
      expect(cells('[data-testid="blocker"]')).toEqual(['Your plan is empty.']);
    });

    it('submits at the prices on screen, empties the tray and refreshes the account', async () => {
      await open('/tools');
      await review(sellAndBuy);
      submitButton().click();
      await settle();

      expect(orderApi.submitOrder.mock.calls.map(([request]) => request)).toEqual([
        { accountId: 'a1', symbol: 'AAPL', side: 'SELL', quantity: 2, quotedPrice: 149.9, orderType: 'MARKET' },
        { accountId: 'a1', symbol: 'MSFT', side: 'BUY', quantity: 1, quotedPrice: 400.1, orderType: 'MARKET' }
      ]);
      expect(cells('[data-testid="review-sheet"] li')).toEqual(['Sell 2 AAPL Filled', 'Buy 1 MSFT Filled']);
      expect(cells('[data-testid="review-summary"]')).toEqual(['2 filled.']);
      expect(plan().isEmpty()).toBe(true);
      expect(holdingsApi.getHoldings).toHaveBeenCalledTimes(2);

      q<HTMLButtonElement>('[data-testid="done"]')!.click();
      harness.detectChanges();
      expect(sheet()).toBeNull();
    });

    it('keeps a rejected order in the tray to fix and retry', async () => {
      orderApi.submitOrder.mockImplementation((request: OrderSubmissionRequest) => request.symbol === 'MSFT'
        ? throwError(() => new HttpErrorResponse({ status: 422, error: { message: 'Insufficient funds - order rejected' } }))
        : of(filled(request)));
      await open('/tools');
      await review(sellAndBuy);
      submitButton().click();
      await settle();
      expect(cells('[data-testid="review-sheet"] li')).toEqual([
        'Sell 2 AAPL Filled', 'Buy 1 MSFT Rejected Insufficient funds - order rejected'
      ]);
      expect(cells('[data-testid="review-summary"]')).toEqual(['1 filled · 1 rejected.']);
      expect(plan().orders().map(o => o.symbol)).toEqual(['MSFT']);
    });

    it('summarises open, settling, skipped and unknown orders', async () => {
      const replies: (OrderStatus | 'none')[] = ['OPEN', 'ACCEPTED', 'none'];
      orderApi.submitOrder.mockImplementation((request: OrderSubmissionRequest) => {
        const reply = replies.shift()!;
        return reply === 'none' ? throwError(() => new HttpErrorResponse({ status: 0 })) : of(filled(request, reply));
      });
      await open('/tools');
      // The queued sale raises nothing; the settling buy and the unknown one spend $550.20 of $1,000,
      // leaving too little for two more MSFT.
      await review(
        ['rebalance', 'a1', [{ symbol: 'AAPL', side: 'SELL', quantity: 1 }, { symbol: 'AAPL', side: 'BUY', quantity: 1 }]],
        ['basket', 'a1', [{ symbol: 'MSFT', side: 'BUY', quantity: 1 }]],
        ['draw', 'a1', [{ symbol: 'MSFT', side: 'BUY', quantity: 2 }]]
      );
      submitButton().click();
      await settle();
      expect(cells('[data-testid="review-sheet"] li')).toEqual([
        'Sell 1 AAPL Open', 'Buy 1 AAPL Accepted (settling)',
        `Buy 1 MSFT Unknown ${UNKNOWN_MESSAGE}`, `Buy 2 MSFT Skipped ${SKIPPED_MESSAGE}`
      ]);
      expect(cells('[data-testid="review-summary"]')).toEqual(['1 filled · 1 open · 1 skipped · 1 unknown (1 still settling).']);
      expect(plan().orders().map(o => `${o.quantity} ${o.symbol}`)).toEqual(['2 MSFT']);
    });

    it('stays shut to closing and leaving until every request has answered, and sends nothing twice', async () => {
      const pending = new Subject<OrderSubmissionResponse>();
      orderApi.submitOrder.mockReturnValueOnce(pending);
      await open('/tools');
      await review(sellAndBuy);
      const button = submitButton();
      button.click();
      button.click();
      harness.detectChanges();
      expect(orderApi.submitOrder).toHaveBeenCalledTimes(1);

      // Backdrop, Escape and the Done button all wait.
      q<HTMLElement>('app-plan-review .backdrop')!.click();
      sheet()!.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
      harness.detectChanges();
      expect(sheet()).not.toBeNull();
      expect(q<HTMLButtonElement>('[data-testid="done"]')!.disabled).toBe(true);
      const page = harness.fixture.debugElement.query(By.directive(ToolsPage)).componentInstance as ToolsPage;
      expect(page.canLeave()).toBe(false);

      pending.next(filled(orderApi.submitOrder.mock.calls[0][0]));
      pending.complete();
      await settle();
      expect(orderApi.submitOrder).toHaveBeenCalledTimes(2);
      expect(page.canLeave()).toBe(true);
      sheet()!.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
      harness.detectChanges();
      expect(sheet()).toBeNull();
    });

    it('counts cash already held by open orders', async () => {
      orderApi.getOpenOrders.mockReturnValue(of([{ orderId: 'x', accountId: 'a1', symbol: 'NVDA', side: 'BUY', quantity: 1,
        status: 'OPEN', orderType: 'LIMIT', reservedCash: 950 } as never]));
      await open('/tools');
      await review(['basket', 'a1', [{ symbol: 'MSFT', side: 'BUY', quantity: 1 }]]);
      submitButton().click();
      await settle();
      expect(orderApi.submitOrder).not.toHaveBeenCalled();
      expect(cells('[data-testid="review-summary"]')).toEqual(['1 skipped.']);
    });

    it('still opens when open orders can\'t be checked, and says so', async () => {
      orderApi.getOpenOrders.mockReturnValue(throwError(() => new HttpErrorResponse({ status: 503 })));
      await open('/tools');
      await review(sellAndBuy);
      expect(submitButton().disabled).toBe(false);
      expect(cells('[data-testid="review-sheet"] [role="alert"]')[0]).toContain('Couldn’t check your open orders');
    });

    it('holds the account-switch prompt back until the sheet closes', async () => {
      await open('/tools?account=a1');
      await review(sellAndBuy);
      await router.navigateByUrl('/tools?account=a2');
      await harness.fixture.whenStable();
      harness.detectChanges();
      expect(q('[data-testid="switch-dialog"]')).toBeNull();
      q<HTMLButtonElement>('[data-testid="review-sheet"] footer .secondary')!.click();
      harness.detectChanges();
      expect(q('[data-testid="switch-dialog"]')).not.toBeNull();
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
