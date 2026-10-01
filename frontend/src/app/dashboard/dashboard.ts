import { Component, DestroyRef, OnInit, ViewChild, computed, effect, inject, signal, untracked } from '@angular/core';
import { CommonModule } from '@angular/common';
import { takeUntilDestroyed, toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Router } from '@angular/router';
import { map } from 'rxjs';
import { AccountValuationService } from '../services/account-valuation';
import { OrderHistoryItem, OrderService, OrderSide } from '../services/order';
import { PriceStreamService } from '../services/price-stream';
import { PORTFOLIO_RANGES, PortfolioHistory, PortfolioRange, PortfolioService } from '../services/portfolio';
import { HoldingView, MARKET_INDICES, MarketIndex, TradeAccount } from './models';
import { TickerStripComponent } from './ticker-strip/ticker-strip';
import { SymbolSearchComponent } from './symbol-search/symbol-search';
import { ChartPoint, LineChartComponent } from './line-chart/line-chart';
import { PositionsListComponent } from './positions-list/positions-list';
import { RecentActivityComponent } from './recent-activity/recent-activity';
import { TradePanelComponent } from './trade-panel/trade-panel';
import { AccountSwitcherComponent } from './account-switcher/account-switcher';
import { AccountsOverviewComponent } from './accounts-overview/accounts-overview';
import { AccountDetailsComponent } from './account-details/account-details';
import { RollingNumberComponent } from '../shared/rolling-number';
import { direction, formatMoney, formatSignedMoney, formatSignedPercent, percentChange } from '../shared/format';

/** Bucket size of market-data's intraday closes (see MarketDataService.getIntradayCloses). */
const INTRADAY_INTERVAL_MS = 300_000;

/**
 * The signed-in home page. At /dashboard it shows every account combined, with a breakdown by
 * account; at /dashboard/:accountId the same layout is scoped to one account.
 */
@Component({
    selector: 'app-dashboard',
    imports: [
        CommonModule, TickerStripComponent, SymbolSearchComponent, LineChartComponent,
        PositionsListComponent, RecentActivityComponent, TradePanelComponent, RollingNumberComponent,
        AccountSwitcherComponent, AccountsOverviewComponent, AccountDetailsComponent
    ],
    templateUrl: './dashboard.html',
    styleUrl: './dashboard.css',
    providers: [AccountValuationService]
})
export class DashboardComponent implements OnInit {
  @ViewChild(TradePanelComponent) tradePanel?: TradePanelComponent;

  readonly ranges = PORTFOLIO_RANGES;

  private readonly valuation = inject(AccountValuationService);
  private readonly orderService = inject(OrderService);
  private readonly portfolioService = inject(PortfolioService);
  private readonly stream = inject(PriceStreamService);
  private readonly destroyRef = inject(DestroyRef);
  private readonly router = inject(Router);

  /** The account the dashboard is scoped to (/dashboard/:accountId), or null for all accounts. */
  readonly accountId = toSignal(inject(ActivatedRoute).paramMap.pipe(map(params => params.get('accountId'))),
    { initialValue: null });

  // ---- Loaded state ----
  // Accounts, balances and positions, valued live - shared with the Accounts page.
  private readonly holdingsResponse = this.valuation.holdingsResponse;
  private readonly balance = this.valuation.balance;
  readonly accountError = this.valuation.accountError;

  readonly range = signal<PortfolioRange>('1D');
  readonly history = signal<PortfolioHistory | null>(null);
  readonly historyLoading = signal(true);
  readonly historyError = signal<string | null>(null);

  readonly orders = signal<OrderHistoryItem[]>([]);
  readonly ordersLoading = signal(true);
  readonly ordersError = signal<string | null>(null);

  readonly selectedSymbol = signal<string | null>(null);
  readonly scrubIndex = signal<number | null>(null);

  // ---- Derived state ----
  readonly accountsLoading = this.valuation.accountsLoading;
  readonly prices = this.valuation.prices;
  readonly previousCloses = this.valuation.previousCloses;

  /** Every account valued live, in the API's order so each keeps its colour. */
  readonly accounts = this.valuation.accounts;

  /** The scoped account's view, or null on the all-accounts overview. */
  readonly selectedAccount = computed(() => this.accounts().find(a => a.accountId === this.accountId()) ?? null);

  readonly isAccountView = computed(() => this.accountId() !== null);

  /** The switcher and accounts card only earn their space once there's more than one account. */
  readonly multiAccount = this.valuation.multiAccount;

  /** Holdings in scope - one account, or combined across accounts - largest position first. */
  readonly holdings = computed<HoldingView[]>(() => {
    const response = this.holdingsResponse();
    if (!response) {
      return [];
    }
    const accountId = this.accountId();
    const scoped = accountId ? response.accounts.filter(a => a.accountId === accountId) : response.accounts;
    return this.valuation.priceHoldings(scoped.flatMap(a => this.valuation.openPositions(a.positions)));
  });

  readonly cash = computed(() => {
    const accountId = this.accountId();
    return this.balance()?.accounts
      .filter(a => !accountId || a.accountId === accountId)
      .reduce((sum, a) => sum + a.balance, 0) ?? null;
  });

  readonly marketValue = computed(() => this.holdings().reduce((sum, h) => sum + (h.marketValue ?? 0), 0));

  readonly portfolioValue = computed(() => {
    const cash = this.cash();
    return cash === null || this.holdingsResponse() === null ? null : cash + this.marketValue();
  });

  readonly todaysReturn = computed(() => {
    const changes = this.holdings().filter(h => h.dayChange !== null);
    return changes.length ? changes.reduce((sum, h) => sum + h.dayChange!, 0) : null;
  });

  readonly todaysReturnPercent = computed(() => {
    const change = this.todaysReturn();
    const value = this.marketValue();
    return change === null || value - change === 0 ? null : (change / (value - change)) * 100;
  });

  readonly totalReturn = computed(() => {
    const withReturn = this.holdings().filter(h => h.totalReturn !== null);
    return withReturn.length ? withReturn.reduce((sum, h) => sum + h.totalReturn!, 0) : null;
  });

  /**
   * History points, with the final "now" point kept current from the live value. Portfolio history
   * is client-wide, so an account's chart is built from its holdings' intraday prices instead.
   */
  readonly chartPoints = computed<ChartPoint[]>(() => {
    let points: ChartPoint[];
    if (this.isAccountView()) {
      const series = this.selectedAccount()?.intraday ?? [];
      const now = Date.now();
      points = series.map((value, i) => ({ time: now - (series.length - 1 - i) * INTRADAY_INTERVAL_MS, value }));
    } else {
      const history = this.history();
      points = history ? history.points.map(p => ({ time: Date.parse(p.timestamp), value: p.value })) : [];
    }
    const live = this.portfolioValue();
    if (live !== null && points.length) {
      points[points.length - 1] = { time: Date.now(), value: live };
    }
    return points;
  });

  readonly baseline = computed(() => this.isAccountView()
    ? this.selectedAccount()?.previousCloseValue ?? null
    : this.history()?.startValue ?? null);

  /** Chart states for whichever source the current scope charts from. */
  readonly chartLoading = computed(() => this.isAccountView()
    ? this.accountsLoading() || (this.holdings().length > 0 && this.chartPoints().length === 0)
    : this.historyLoading());

  readonly chartError = computed(() => this.isAccountView() ? null : this.historyError());

  /** An account with nothing invested has no intraday movement to chart. */
  readonly chartEmpty = computed(() => this.isAccountView() && !this.accountsLoading() && this.holdings().length === 0);

  /**
   * Account charts cover today only, so they have no range to pick. Longer ranges need the history
   * API to accept an account.
   */
  readonly visibleRanges = computed(() => this.isAccountView() ? [] : this.ranges);

  readonly activeRange = computed<PortfolioRange>(() => this.isAccountView() ? '1D' : this.range());

  readonly headlineValue = computed(() => {
    const index = this.scrubIndex();
    const points = this.chartPoints();
    return index !== null && points[index] ? points[index].value : this.portfolioValue();
  });

  readonly headlineChange = computed(() => {
    const value = this.headlineValue();
    const baseline = this.baseline();
    return value === null || baseline === null ? null : value - baseline;
  });

  readonly headlineChangePercent = computed(() => percentChange(this.headlineValue(), this.baseline()));

  /** The chart's colour follows the whole range's performance, not the scrubbed point. */
  readonly tone = computed(() => {
    const live = this.portfolioValue();
    const baseline = this.baseline();
    return live === null || baseline === null ? 'flat' : direction(live - baseline);
  });

  readonly headlineLabel = computed(() => {
    const index = this.scrubIndex();
    const points = this.chartPoints();
    if (index !== null && points[index]) {
      const date = new Date(points[index].time);
      const withTime = this.isAccountView() || (this.history()?.intervalSeconds ?? 86400) < 86400;
      return date.toLocaleString('en-US', withTime
        ? { month: 'short', day: 'numeric', hour: 'numeric', minute: '2-digit' }
        : { month: 'short', day: 'numeric', year: 'numeric' });
    }
    return this.ranges.find(r => r.range === this.activeRange())?.label ?? '';
  });

  readonly indices = computed<MarketIndex[]>(() => {
    const prices = this.prices();
    const closes = this.previousCloses();
    return MARKET_INDICES.map(({ symbol, label }) => ({
      symbol, label, price: prices[symbol] ?? null, previousClose: closes[symbol] ?? null
    }));
  });

  readonly tradeAccounts = computed<TradeAccount[]>(() => {
    const balance = this.balance();
    const holdings = this.holdingsResponse();
    if (!balance) {
      return [];
    }
    return balance.accounts.map(account => {
      const positions = holdings?.accounts.find(a => a.accountId === account.accountId)?.positions ?? [];
      return {
        accountId: account.accountId,
        accountNumber: account.accountNumber,
        buyingPower: account.balance,
        shares: Object.fromEntries(positions.map(p => [p.symbol, p.quantity]))
      };
    });
  });

  /** Eyebrow above the headline value: what the figure covers. */
  readonly heroTitle = computed(() => {
    if (this.isAccountView()) {
      return this.selectedAccount()?.name ?? 'Account';
    }
    return this.multiAccount() ? 'Total value' : 'Investing';
  });

  readonly accountLabel = computed(() => {
    if (this.isAccountView()) {
      return this.selectedAccount()?.accountNumber ?? '';
    }
    const accounts = this.balance()?.accounts ?? [];
    return accounts.length === 1 ? accounts[0].accountNumber : accounts.length > 1 ? `${accounts.length} accounts` : '';
  });

  readonly names = computed<Record<string, string>>(() => ({
    ...this.valuation.marketNames(),
    ...Object.fromEntries(this.holdings().map(h => [h.symbol, h.name]))
  }));

  readonly selectedName = computed(() => {
    const symbol = this.selectedSymbol();
    return symbol ? this.names()[symbol] ?? null : null;
  });

  readonly selectedPrice = computed(() => {
    const symbol = this.selectedSymbol();
    return symbol ? this.prices()[symbol] ?? null : null;
  });

  money = formatMoney;
  signedMoney = formatSignedMoney;
  signedPercent = formatSignedPercent;
  dir = direction;

  constructor() {
    // Keep the live stream subscribed to everything on screen.
    effect(() => {
      const symbols = [
        ...MARKET_INDICES.map(i => i.symbol),
        ...this.holdings().map(h => h.symbol),
        ...(this.selectedSymbol() ? [this.selectedSymbol()!] : [])
      ];
      this.stream.watch(symbols);
    }, { allowSignalWrites: true });

    // Changing scope drops any scrub and points the trade panel at something held in the new scope.
    effect(() => {
      this.accountId();
      untracked(() => {
        this.scrubIndex.set(null);
        const held = this.holdings();
        if (held.length && !held.some(h => h.symbol === this.selectedSymbol())) {
          this.selectedSymbol.set(held[0].symbol);
        }
      });
    });

    // An account that isn't the client's, or has closed, falls back to the overview.
    effect(() => {
      const accountId = this.accountId();
      const balance = this.balance();
      if (accountId && balance && !balance.accounts.some(a => a.accountId === accountId)) {
        untracked(() => this.router.navigate(['/dashboard'], { replaceUrl: true }));
      }
    });
  }

  ngOnInit(): void {
    this.loadAccounts();
    this.loadHistory();
    this.loadOrders();
    this.valuation.loadDailyContext(MARKET_INDICES.map(i => i.symbol), false);
  }

  loadAccounts(): void {
    this.valuation.load(() => {
      if (!this.selectedSymbol()) {
        this.selectedSymbol.set(this.holdings()[0]?.symbol ?? null);
      }
    });
  }

  loadHistory(): void {
    this.historyLoading.set(true);
    this.historyError.set(null);
    this.scrubIndex.set(null);
    const range = this.range();
    this.portfolioService.getHistory(range)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: history => {
          if (range === this.range()) {
            this.history.set(history);
            this.historyLoading.set(false);
          }
        },
        error: err => {
          if (range === this.range()) {
            this.historyError.set(err.status === 503
              ? 'Market data is unavailable right now.'
              : 'We couldn’t load your portfolio history.');
            this.historyLoading.set(false);
          }
        }
      });
  }

  loadOrders(): void {
    this.ordersError.set(null);
    this.orderService.getOrderHistory(0, 5)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: page => {
          this.orders.set(page.content);
          this.ordersLoading.set(false);
        },
        error: () => {
          this.ordersError.set('We couldn’t load your recent orders.');
          this.ordersLoading.set(false);
        }
      });
  }

  setRange(range: PortfolioRange): void {
    if (range !== this.range() && !this.isAccountView()) {
      this.range.set(range);
      this.loadHistory();
    }
  }

  selectSymbol(symbol: string): void {
    this.selectedSymbol.set(symbol);
    this.valuation.loadDailyContext([symbol], false);
  }

  /** Search pick or position Buy/Sell: load the symbol and put the cursor in the shares field. */
  trade(symbol: string, side: OrderSide = 'BUY'): void {
    this.selectSymbol(symbol);
    // Let the panel receive the new symbol before opening it on a side.
    setTimeout(() => {
      this.tradePanel?.open(side);
      document.getElementById('trade-panel')?.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
    });
  }

  onOrderPlaced(): void {
    this.loadAccounts();
    this.loadHistory();
    this.loadOrders();
  }
}
