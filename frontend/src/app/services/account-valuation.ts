import { DestroyRef, Injectable, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { forkJoin } from 'rxjs';
import { AccountSummary, ClientHoldings, HoldingsService, PositionItem } from './holdings';
import { BalanceResponse, BalanceService } from './balance';
import { MarketDataService } from './market-data';
import { PriceStreamService } from './price-stream';
import { ACCOUNT_COLORS, AccountView, HoldingView, accountMask, accountName } from '../dashboard/models';
import { percentChange } from '../shared/format';

/**
 * The client's accounts valued live: balances, positions and account details from account-app,
 * priced from market data and the live stream. The dashboard and the Accounts page both read
 * `accounts()`, so an account's name, colour, value and share of the total match on both.
 *
 * Provided per page, not app-wide, so each visit loads fresh and nothing about one client outlives
 * the page (or a logout) for the next.
 */
@Injectable()
export class AccountValuationService {
  private readonly holdingsService = inject(HoldingsService);
  private readonly balanceService = inject(BalanceService);
  private readonly marketData = inject(MarketDataService);
  private readonly stream = inject(PriceStreamService);
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
  private readonly intraday = signal<Record<string, number[]>>({});
  private readonly requestedCloses = new Set<string>();
  private readonly requestedIntraday = new Set<string>();
  private snapshotRequested = false;

  // ---- Derived state ----
  readonly accountsLoading = computed(() => this.holdingsResponse() === null || this.balance() === null);

  readonly prices = computed<Record<string, number>>(() => ({ ...this.snapshotPrices(), ...this.stream.prices() }));

  /** Every account valued live, in the API's order so each keeps its colour. */
  readonly accounts = computed<AccountView[]>(() => {
    const holdings = this.holdingsResponse();
    const balance = this.balance();
    if (!holdings || !balance) {
      return [];
    }
    const summaries = this.accountSummaries();
    const views = balance.accounts.map((account, index) => {
      const positions = this.openPositions(holdings.accounts.find(a => a.accountId === account.accountId)?.positions);
      const priced = this.priceHoldings(positions);
      const summary = summaries.find(s => s.accountId === account.accountId);
      const marketValue = priced.reduce((sum, h) => sum + (h.marketValue ?? 0), 0);
      const dayChanges = priced.filter(h => h.dayChange !== null);
      const dayChange = dayChanges.length ? dayChanges.reduce((sum, h) => sum + h.dayChange!, 0) : null;
      const returns = priced.filter(h => h.totalReturn !== null);
      const previousCloseValue = priced.every(h => h.previousClose !== null)
        ? account.balance + priced.reduce((sum, h) => sum + h.previousClose! * h.quantity, 0)
        : null;
      return {
        accountId: account.accountId,
        accountNumber: account.accountNumber,
        name: accountName(account.accountNumber),
        mask: accountMask(account.accountNumber),
        color: ACCOUNT_COLORS[index % ACCOUNT_COLORS.length],
        status: summary?.status ?? null,
        tradingEnabled: summary?.tradingEnabled ?? null,
        openedAt: summary?.createdAt ?? null,
        inactiveSince: summary?.inactiveSince ?? null,
        cash: account.balance,
        marketValue,
        value: account.balance + marketValue,
        dayChange,
        dayChangePercent: dayChange === null ? null : percentChange(marketValue, marketValue - dayChange),
        totalReturn: returns.length ? returns.reduce((sum, h) => sum + h.totalReturn!, 0) : null,
        positionCount: priced.length,
        share: 0,
        intraday: this.intradayValue(priced, account.balance),
        previousCloseValue
      };
    });
    const total = views.reduce((sum, a) => sum + a.value, 0);
    return views.map(view => ({ ...view, share: total ? view.value / total : 0 }));
  });

  /** The switcher and per-account breakdowns only earn their space once there's more than one account. */
  readonly multiAccount = computed(() => (this.balance()?.accounts.length ?? 0) > 1);

  /**
   * Loads (or reloads, e.g. after a trade or transfer) balances, positions and account details,
   * then the daily price context for every held symbol. The first call also fetches the latest
   * price of every instrument.
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
          onLoaded?.();
        },
        error: err => this.accountError.set(err.error?.message || 'We couldn’t load your accounts.')
      });
  }

  /** Every symbol held in any account. */
  heldSymbols(): string[] {
    return [...new Set((this.holdingsResponse()?.accounts ?? []).flatMap(a => a.positions.map(p => p.symbol)))];
  }

  openPositions(positions: PositionItem[] | undefined): PositionItem[] {
    return (positions ?? []).filter(p => p.quantity !== 0);
  }

  /** Prices positions live, merging the same symbol across accounts, largest position first. */
  priceHoldings(positions: PositionItem[]): HoldingView[] {
    const prices = this.prices();
    const closes = this.previousCloses();
    const intraday = this.intraday();
    const bySymbol = new Map<string, { name: string; quantity: number; cost: number }>();
    for (const p of positions) {
      const entry = bySymbol.get(p.symbol) ?? { name: p.instrumentName, quantity: 0, cost: 0 };
      entry.quantity += p.quantity;
      entry.cost += p.quantity * p.averageCost;
      bySymbol.set(p.symbol, entry);
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
   * Today's value of some holdings plus cash at each intraday bucket, with every symbol's series
   * aligned on its latest bucket. Empty until each holding's intraday prices have loaded.
   */
  private intradayValue(holdings: HoldingView[], cash: number): number[] {
    if (!holdings.length || holdings.some(h => h.intraday.length < 2)) {
      return [];
    }
    const length = Math.min(...holdings.map(h => h.intraday.length));
    return Array.from({ length }, (_, i) => holdings.reduce(
      (sum, h) => sum + h.quantity * h.intraday[h.intraday.length - length + i], cash));
  }
}
