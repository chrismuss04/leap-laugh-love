import { Component, DestroyRef, OnInit, ViewChild, computed, effect, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { forkJoin } from 'rxjs';
import { HoldingsService, ClientHoldings } from '../services/holdings';
import { BalanceResponse, BalanceService } from '../services/balance';
import { OrderHistoryItem, OrderService, OrderSide } from '../services/order';
import { MarketDataService } from '../services/market-data';
import { PriceStreamService } from '../services/price-stream';
import { PORTFOLIO_RANGES, PortfolioHistory, PortfolioRange, PortfolioService } from '../services/portfolio';
import { HoldingView, MARKET_INDICES, MarketIndex, TradeAccount } from './models';
import { TickerStripComponent } from './ticker-strip/ticker-strip';
import { SymbolSearchComponent } from './symbol-search/symbol-search';
import { ChartPoint, LineChartComponent } from './line-chart/line-chart';
import { PositionsListComponent } from './positions-list/positions-list';
import { RecentActivityComponent } from './recent-activity/recent-activity';
import { TradePanelComponent } from './trade-panel/trade-panel';
import { direction, formatMoney, formatSignedMoney, formatSignedPercent, percentChange } from '../shared/format';

@Component({
    selector: 'app-dashboard',
    imports: [
        CommonModule, TickerStripComponent, SymbolSearchComponent, LineChartComponent,
        PositionsListComponent, RecentActivityComponent, TradePanelComponent
    ],
    templateUrl: './dashboard.html',
    styleUrl: './dashboard.css'
})
export class DashboardComponent implements OnInit {
  @ViewChild(TradePanelComponent) tradePanel?: TradePanelComponent;

  readonly ranges = PORTFOLIO_RANGES;

  private readonly holdingsService = inject(HoldingsService);
  private readonly balanceService = inject(BalanceService);
  private readonly orderService = inject(OrderService);
  private readonly marketData = inject(MarketDataService);
  private readonly portfolioService = inject(PortfolioService);
  private readonly stream = inject(PriceStreamService);
  private readonly destroyRef = inject(DestroyRef);

  // ---- Loaded state ----
  readonly holdingsResponse = signal<ClientHoldings | null>(null);
  readonly balance = signal<BalanceResponse | null>(null);
  readonly accountError = signal<string | null>(null);

  readonly range = signal<PortfolioRange>('1D');
  readonly history = signal<PortfolioHistory | null>(null);
  readonly historyLoading = signal(true);
  readonly historyError = signal<string | null>(null);

  readonly orders = signal<OrderHistoryItem[]>([]);
  readonly ordersLoading = signal(true);
  readonly ordersError = signal<string | null>(null);

  /** Latest price per symbol from REST, used until the live stream has ticked the symbol. */
  private readonly snapshotPrices = signal<Record<string, number>>({});
  /** Display name per symbol from market data, covering instruments the client doesn't hold. */
  private readonly marketNames = signal<Record<string, string>>({});
  readonly previousCloses = signal<Record<string, number | null>>({});
  private readonly intraday = signal<Record<string, number[]>>({});
  private readonly requestedCloses = new Set<string>();
  private readonly requestedIntraday = new Set<string>();

  readonly selectedSymbol = signal<string | null>(null);
  readonly scrubIndex = signal<number | null>(null);

  // ---- Derived state ----
  readonly accountsLoading = computed(() => this.holdingsResponse() === null || this.balance() === null);

  readonly prices = computed<Record<string, number>>(() => ({ ...this.snapshotPrices(), ...this.stream.prices() }));

  /** Holdings combined across accounts, largest position first. */
  readonly holdings = computed<HoldingView[]>(() => {
    const response = this.holdingsResponse();
    if (!response) {
      return [];
    }
    const prices = this.prices();
    const closes = this.previousCloses();
    const intraday = this.intraday();
    const bySymbol = new Map<string, { name: string; quantity: number; cost: number }>();
    for (const account of response.accounts) {
      for (const p of account.positions) {
        if (p.quantity === 0) {
          continue;
        }
        const entry = bySymbol.get(p.symbol) ?? { name: p.instrumentName, quantity: 0, cost: 0 };
        entry.quantity += p.quantity;
        entry.cost += p.quantity * p.averageCost;
        bySymbol.set(p.symbol, entry);
      }
    }
    return [...bySymbol.entries()].map(([symbol, { name, quantity, cost }]) => {
      const price = prices[symbol] ?? null;
      const previousClose = closes[symbol] ?? null;
      const marketValue = price !== null ? price * quantity : null;
      const dayChange = price !== null && previousClose !== null ? (price - previousClose) * quantity : null;
      const totalReturn = marketValue !== null ? marketValue - cost : null;
      return {
        symbol, name, quantity,
        averageCost: quantity ? cost / quantity : 0,
        price, previousClose, marketValue, dayChange,
        dayChangePercent: percentChange(price, previousClose),
        totalReturn,
        totalReturnPercent: totalReturn !== null && cost ? (totalReturn / cost) * 100 : null,
        intraday: intraday[symbol] ?? []
      };
    }).sort((a, b) => (b.marketValue ?? 0) - (a.marketValue ?? 0));
  });

  readonly cash = computed(() => this.balance()?.accounts.reduce((sum, a) => sum + a.balance, 0) ?? null);

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

  /** History points, with the final "now" point kept current from the live value. */
  readonly chartPoints = computed<ChartPoint[]>(() => {
    const history = this.history();
    if (!history) {
      return [];
    }
    const points = history.points.map(p => ({ time: Date.parse(p.timestamp), value: p.value }));
    const live = this.portfolioValue();
    if (live !== null && points.length) {
      points[points.length - 1] = { time: Date.now(), value: live };
    }
    return points;
  });

  readonly baseline = computed(() => this.history()?.startValue ?? null);

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
      const withTime = (this.history()?.intervalSeconds ?? 86400) < 86400;
      return date.toLocaleString('en-US', withTime
        ? { month: 'short', day: 'numeric', hour: 'numeric', minute: '2-digit' }
        : { month: 'short', day: 'numeric', year: 'numeric' });
    }
    return this.ranges.find(r => r.range === this.range())?.label ?? '';
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

  readonly accountLabel = computed(() => {
    const accounts = this.balance()?.accounts ?? [];
    return accounts.length === 1 ? accounts[0].accountNumber : accounts.length > 1 ? `${accounts.length} accounts` : '';
  });

  readonly names = computed<Record<string, string>>(() => ({
    ...this.marketNames(),
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
  }

  ngOnInit(): void {
    this.loadAccounts();
    this.loadHistory();
    this.loadOrders();

    this.marketData.getAllLatestPrices()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe(prices => {
        this.snapshotPrices.update(current => ({
          ...Object.fromEntries(prices.map(p => [p.symbol, Number(p.price)])),
          ...current
        }));
        this.marketNames.set(Object.fromEntries(
          prices.filter(p => p.name).map(p => [p.symbol, p.name as string])));
      });
    this.loadDailyContext(MARKET_INDICES.map(i => i.symbol), false);
  }

  loadAccounts(): void {
    this.accountError.set(null);
    forkJoin({ holdings: this.holdingsService.getHoldings(), balance: this.balanceService.getBalance() })
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: ({ holdings, balance }) => {
          this.holdingsResponse.set(holdings);
          this.balance.set(balance);
          const symbols = [...new Set(holdings.accounts.flatMap(a => a.positions.map(p => p.symbol)))];
          this.loadDailyContext(symbols, true);
          if (!this.selectedSymbol()) {
            this.selectedSymbol.set(this.holdings()[0]?.symbol ?? null);
          }
        },
        error: err => this.accountError.set(err.error?.message || 'We couldn’t load your accounts.')
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
    if (range !== this.range()) {
      this.range.set(range);
      this.loadHistory();
    }
  }

  selectSymbol(symbol: string): void {
    this.selectedSymbol.set(symbol);
    this.loadDailyContext([symbol], false);
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

  /**
   * Previous close for day-change figures, plus an intraday series for sparklines when wanted.
   * Each is fetched once per symbol per visit - both describe completed buckets, which the live
   * stream supersedes for the current price.
   */
  private loadDailyContext(symbols: string[], withIntraday: boolean): void {
    for (const symbol of symbols) {
      if (!this.requestedCloses.has(symbol)) {
        this.requestedCloses.add(symbol);
        this.marketData.getPreviousClose(symbol)
          .pipe(takeUntilDestroyed(this.destroyRef))
          .subscribe(close => this.previousCloses.update(current => ({ ...current, [symbol]: close })));
      }
      if (withIntraday && !this.requestedIntraday.has(symbol)) {
        this.requestedIntraday.add(symbol);
        this.marketData.getIntradayCloses(symbol)
          .pipe(takeUntilDestroyed(this.destroyRef))
          .subscribe(closes => this.intraday.update(current => ({ ...current, [symbol]: closes })));
      }
    }
  }
}
