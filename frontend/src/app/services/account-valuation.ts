import { DestroyRef, Injectable, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { forkJoin } from 'rxjs';
import { AccountHoldings, AccountSummary, ClientHoldings, HoldingsService, PositionItem } from './holdings';
import { BalanceResponse, BalanceService } from './balance';
import { IntradayCloses, MarketDataService } from './market-data';
import { MarketStatusService } from './market-status';
import { sessionsAt } from '../shared/market-session';
import { PortfolioHistory, PortfolioService } from './portfolio';
import { PriceStreamService } from './price-stream';
import { ACCOUNT_COLORS, AccountView, HoldingView, accountMask, accountName } from '../dashboard/models';

/** A gain or loss today, and the percentage it is of what was at stake. */
export interface DayReturn {
  change: number | null;
  percent: number | null;
}

/** Positions of one symbol merged across accounts, with what they did today. */
interface MergedPosition {
  symbol: string;
  name: string;
  quantity: number;
  cost: number;
  quantityAtOpen: number;
  todayCashFlow: number;
}

/**
 * The client's accounts valued live: balances, positions and account details from account-app,
 * priced from market data and the live stream. The dashboard and the Accounts page both read
 * `accounts()`, so an account's name, colour, value and share of the total match on both.
 *
 * Totals - an account's value and day change - are priced at the stream's settled prices, so a
 * figure summed over many holdings moves every few seconds rather than on every tick. A single
 * holding's row can use the live `prices()`.
 *
 * Provided per page, not app-wide, so each visit loads fresh and nothing about one client outlives
 * the page (or a logout) for the next.
 */
@Injectable()
export class AccountValuationService {
  private readonly holdingsService = inject(HoldingsService);
  private readonly balanceService = inject(BalanceService);
  private readonly marketData = inject(MarketDataService);
  private readonly portfolio = inject(PortfolioService);
  private readonly stream = inject(PriceStreamService);
  private readonly market = inject(MarketStatusService);
  private readonly destroyRef = inject(DestroyRef);

  // ---- Loaded state ----
  readonly holdingsResponse = signal<ClientHoldings | null>(null);
  readonly balance = signal<BalanceResponse | null>(null);
  readonly accountSummaries = signal<AccountSummary[]>([]);
  readonly accountError = signal<string | null>(null);

  /** Latest price per symbol from REST, used until the live stream has ticked the symbol. */
  private readonly snapshotPrices = signal<Record<string, number>>({});
  /** Display name per symbol from market data, covering instruments the client doesn't hold. */
  readonly marketNames = signal<Record<string, string>>({});
  readonly previousCloses = signal<Record<string, number | null>>({});
  private readonly intraday = signal<Record<string, IntradayCloses>>({});
  /** Each account's value today, from account-app's history, for sparklines. */
  private readonly dayHistories = signal<Record<string, PortfolioHistory>>({});
  private readonly requestedCloses = new Set<string>();
  private readonly requestedIntraday = new Set<string>();
  private snapshotRequested = false;

  // ---- Derived state ----
  readonly accountsLoading = computed(() => this.holdingsResponse() === null || this.balance() === null);

  /** Latest price per symbol, tick by tick: for a single holding's figures. */
  readonly prices = computed<Record<string, number>>(() => ({ ...this.snapshotPrices(), ...this.stream.prices() }));

  /** Prices as of the stream's last settle: for totals over many holdings. */
  readonly settledPrices = computed<Record<string, number>>(
    () => ({ ...this.snapshotPrices(), ...this.stream.settledPrices() }));

  /**
   * The hours of the trading day under way, from account-app's day history (any account's: the day
   * is the same for all), so stock sparklines frame the day as the 1D chart does. Null until it
   * loads, and with market hours off.
   */
  readonly dayHours = computed(() => Object.values(this.dayHistories()).find(history => history.hours)?.hours ?? null);

  /** Every account valued live, in the API's order so each keeps its colour. */
  readonly accounts = computed<AccountView[]>(() => {
    const holdings = this.holdingsResponse();
    const balance = this.balance();
    if (!holdings || !balance) {
      return [];
    }
    const prices = this.settledPrices();
    const summaries = this.accountSummaries();
    const histories = this.dayHistories();
    const views = balance.accounts.map((account, index) => {
      const accountHoldings = holdings.accounts.find(a => a.accountId === account.accountId);
      const priced = this.priceHoldings(this.openPositions(accountHoldings?.positions), prices);
      const summary = summaries.find(s => s.accountId === account.accountId);
      const marketValue = priced.reduce((sum, h) => sum + (h.marketValue ?? 0), 0);
      const day = this.dayReturn(accountHoldings ? [accountHoldings] : [], prices);
      const returns = priced.filter(h => h.totalReturn !== null);
      const history = histories[account.accountId];
      return {
        accountId: account.accountId,
        accountNumber: account.accountNumber,
        name: accountName(account.accountNumber, summary?.accountSeq),
        mask: accountMask(account.accountNumber),
        color: ACCOUNT_COLORS[index % ACCOUNT_COLORS.length],
        status: summary?.status ?? null,
        tradingEnabled: summary?.tradingEnabled ?? null,
        openedAt: summary?.createdAt ?? null,
        inactiveSince: summary?.inactiveSince ?? null,
        cash: account.balance,
        marketValue,
        value: account.balance + marketValue,
        dayChange: day.change,
        dayChangePercent: day.percent,
        totalReturn: returns.length ? returns.reduce((sum, h) => sum + h.totalReturn!, 0) : null,
        positionCount: priced.length,
        share: 0,
        // The history ends on "now"; the live value takes that place wherever the series is drawn.
        intraday: history ? history.points.slice(0, -1).map(p => p.value) : [],
        intradaySessions: history ? history.points.slice(0, -1).map(p => p.session) : null,
        dayStartValue: history?.startValue ?? null
      };
    });
    const total = views.reduce((sum, a) => sum + a.value, 0);
    return views.map(view => ({ ...view, share: total ? view.value / total : 0 }));
  });

  /** The switcher and per-account breakdowns only earn their space once there's more than one account. */
  readonly multiAccount = computed(() => (this.balance()?.accounts.length ?? 0) > 1);

  /**
   * Loads (or reloads, e.g. after a trade or transfer) balances, positions and account details,
   * then the daily price context for every symbol traded or held, and each account's day so far.
   * The first call also fetches the latest price of every instrument.
   */
  load(onLoaded?: () => void): void {
    if (!this.snapshotRequested) {
      this.snapshotRequested = true;
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
    }

    this.accountError.set(null);
    forkJoin({
      holdings: this.holdingsService.getHoldings(),
      balance: this.balanceService.getBalance(),
      summaries: this.holdingsService.getAccounts()
    })
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: ({ holdings, balance, summaries }) => {
          this.holdingsResponse.set(holdings);
          this.balance.set(balance);
          this.accountSummaries.set(summaries);
          this.loadDailyContext(this.heldSymbols(), true);
          this.loadDailyContext(this.closedSymbols(), false);
          this.loadDayHistories(balance.accounts.map(a => a.accountId));
          onLoaded?.();
        },
        error: err => this.accountError.set(err.error?.message || 'We couldn’t load your accounts.')
      });
  }

  /** Every symbol held in any account. */
  heldSymbols(): string[] {
    return [...new Set((this.holdingsResponse()?.accounts ?? []).flatMap(a => a.positions.map(p => p.symbol)))];
  }

  /** Symbols sold off today in any account, which still need a previous close for today's return. */
  private closedSymbols(): string[] {
    return [...new Set((this.holdingsResponse()?.accounts ?? []).flatMap(a => (a.closedToday ?? []).map(p => p.symbol)))];
  }

  openPositions(positions: PositionItem[] | undefined): PositionItem[] {
    return (positions ?? []).filter(p => p.quantity !== 0);
  }

  /**
   * Prices positions, merging the same symbol across accounts, largest position first. Live by
   * default; pass `settledPrices()` for figures that feed a total.
   */
  priceHoldings(positions: PositionItem[], prices: Record<string, number> = this.prices()): HoldingView[] {
    const closes = this.previousCloses();
    const intraday = this.intraday();
    const status = this.market.status();
    return this.merge(positions).map(position => {
      const { symbol, name, quantity, cost } = position;
      const price = prices[symbol] ?? null;
      const previousClose = closes[symbol] ?? null;
      const marketValue = price !== null ? price * quantity : null;
      const day = dayFigures(position, price, previousClose);
      const totalReturn = marketValue !== null ? marketValue - cost : null;
      return {
        symbol, name, quantity,
        averageCost: quantity ? cost / quantity : 0,
        price, previousClose, marketValue,
        dayChange: day.change,
        dayChangePercent: day.percent,
        totalReturn,
        totalReturnPercent: totalReturn !== null && cost ? (totalReturn / cost) * 100 : null,
        intraday: intraday[symbol]?.closes ?? [],
        intradayTimes: intraday[symbol]?.starts ?? [],
        intradaySessions: sessionsAt(intraday[symbol]?.starts ?? [], status)
      };
    }).sort((a, b) => (b.marketValue ?? 0) - (a.marketValue ?? 0));
  }

  /**
   * What some accounts made today: what their shares made since the previous close, or since
   * they were bought today, plus what shares sold today made before they were sold. Null until a
   * price or previous close it needs has loaded.
   */
  dayReturn(accounts: AccountHoldings[], prices: Record<string, number> = this.settledPrices()): DayReturn {
    const closes = this.previousCloses();
    const positions = accounts.flatMap(a => [...this.openPositions(a.positions), ...(a.closedToday ?? [])]);
    let change: number | null = null;
    let base = 0;
    for (const position of this.merge(positions)) {
      const day = dayFigures(position, prices[position.symbol] ?? null, closes[position.symbol] ?? null);
      if (day.change !== null) {
        change = (change ?? 0) + day.change;
        base += day.base;
      }
    }
    return { change, percent: change !== null && base ? (change / base) * 100 : null };
  }

  /**
   * Previous close for day-change figures, plus an intraday series for sparklines when wanted.
   * Each is fetched once per symbol per visit - both describe completed buckets, which the live
   * stream supersedes for the current price.
   */
  loadDailyContext(symbols: string[], withIntraday: boolean): void {
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

  /**
   * Each account's value since the day began, for its sparkline. Reloaded with the accounts, since
   * a trade or transfer changes it; an account whose history fails just goes without.
   */
  private loadDayHistories(accountIds: string[]): void {
    for (const accountId of accountIds) {
      this.portfolio.getHistory('1D', accountId)
        .pipe(takeUntilDestroyed(this.destroyRef))
        .subscribe({
          next: history => this.dayHistories.update(current => ({ ...current, [accountId]: history })),
          error: () => {}
        });
    }
  }

  /** Positions merged by symbol, keeping each symbol's first-seen name. */
  private merge(positions: PositionItem[]): MergedPosition[] {
    const bySymbol = new Map<string, MergedPosition>();
    for (const p of positions) {
      const entry = bySymbol.get(p.symbol)
        ?? { symbol: p.symbol, name: p.instrumentName, quantity: 0, cost: 0, quantityAtOpen: 0, todayCashFlow: 0 };
      entry.quantity += p.quantity;
      entry.cost += p.quantity * p.averageCost;
      // Without today's figures, every share is taken to have been held since the open.
      entry.quantityAtOpen += p.quantityAtOpen ?? p.quantity;
      entry.todayCashFlow += p.todayCashFlow ?? 0;
      bySymbol.set(p.symbol, entry);
    }
    return [...bySymbol.values()];
  }
}

/**
 * One symbol's gain or loss today: its shares now, less the shares held at the open valued at the
 * previous close, plus the cash today's trades returned. The base is what was at stake - the
 * opening shares at the previous close plus anything spent on more today. Shares bought today are
 * measured from what they cost, not from yesterday's close.
 */
function dayFigures(position: MergedPosition, price: number | null, previousClose: number | null):
    DayReturn & { base: number } {
  const { quantity, quantityAtOpen, todayCashFlow } = position;
  if ((quantity !== 0 && price === null) || (quantityAtOpen !== 0 && previousClose === null)) {
    return { change: null, percent: null, base: 0 };
  }
  const opening = quantityAtOpen !== 0 ? quantityAtOpen * previousClose! : 0;
  const change = (quantity !== 0 ? quantity * price! : 0) - opening + todayCashFlow;
  const base = opening + Math.max(0, -todayCashFlow);
  return { change, percent: base ? (change / base) * 100 : null, base };
}
