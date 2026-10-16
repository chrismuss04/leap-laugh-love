import { DestroyRef, Injectable, computed, effect, inject, signal, untracked } from '@angular/core';
import { takeUntilDestroyed, toSignal } from '@angular/core/rxjs-interop';
import { NavigationEnd, Router } from '@angular/router';
import { filter, map } from 'rxjs';
import { AccountValuationService } from '../services/account-valuation';
import { MarketDataService } from '../services/market-data';
import { PriceStreamService } from '../services/price-stream';
import { AccountView, HoldingView } from '../dashboard/models';
import { OrderProjection, PlanQuoteOffset, projectOrders } from '../shared/order-plan';
import { ACCOUNT_PARAM } from './tools-catalog';
import { TradePlanService } from './trade-plan';

/** One slice of an account's allocation: a holding, the rest of its holdings, or cash. */
export interface AllocationSlice {
  key: string;
  label: string;
  value: number;
  /** Fraction (0-1) of the account's value. */
  share: number;
  color: string;
}

/** Holdings beyond this many share one "Other" slice, so a mini donut stays readable. */
const MAX_SLICES = 5;
const SLICE_COLORS = ['#5b9dff', '#a78bfa', '#2dd4bf', '#f5b041', '#f472b6'];
const OTHER_COLOR = '#64748b';
const CASH_COLOR = '#3f3f4a';

/** Donut slices for some holdings and cash: the largest holdings, everything else, then cash. */
export function allocationSlices(holdings: { symbol: string; value: number }[], cash: number): AllocationSlice[] {
  const priced = holdings.filter(h => h.value > 0).sort((a, b) => b.value - a.value);
  const top = priced.slice(0, MAX_SLICES);
  const rest = priced.slice(MAX_SLICES).reduce((sum, h) => sum + h.value, 0);
  const slices = top.map((h, i) => ({ key: h.symbol, label: h.symbol, value: h.value, color: SLICE_COLORS[i] }));
  if (rest > 0) {
    slices.push({ key: 'other', label: 'Other', value: rest, color: OTHER_COLOR });
  }
  if (cash > 0) {
    slices.push({ key: 'cash', label: 'Cash', value: cash, color: CASH_COLOR });
  }
  const total = slices.reduce((sum, s) => sum + s.value, 0);
  return slices.map(s => ({ ...s, share: total ? s.value / total : 0 }));
}

/**
 * Trading Tools: the account every tool acts on, valued live. Provided by the tools page, so the
 * hub and each tool share one load and one account as the client moves between them. The account
 * lives in the URL (?account=<id>) so a tool's link opens on the same account.
 */
@Injectable()
export class ToolsContext {
  private readonly valuation = inject(AccountValuationService);
  private readonly stream = inject(PriceStreamService);
  private readonly marketData = inject(MarketDataService);
  private readonly router = inject(Router);
  private readonly destroyRef = inject(DestroyRef);
  readonly plan = inject(TradePlanService);

  /** The current URL, kept current across navigations between the hub and tools. */
  readonly url = toSignal(
    this.router.events.pipe(filter(event => event instanceof NavigationEnd), map(event => event.urlAfterRedirects)),
    { initialValue: this.router.url });

  /** The current page's path without its query, for links that only change the account. */
  readonly path = computed(() => this.url().split(/[?#]/)[0]);

  private readonly accountParam = computed(() => this.router.parseUrl(this.url()).queryParams[ACCOUNT_PARAM] ?? null);

  readonly accounts = this.valuation.accounts;
  readonly loading = this.valuation.accountsLoading;
  readonly error = this.valuation.accountError;
  readonly multiAccount = this.valuation.multiAccount;
  /** Latest price per symbol: the live stream over a REST snapshot. */
  readonly prices = this.valuation.prices;

  /**
   * The account in the URL; without one, the account the trade plan is for, else the client's
   * first account.
   */
  readonly account = computed<AccountView | null>(() => {
    const accounts = this.accounts();
    return accounts.find(a => a.accountId === this.accountParam())
      ?? accounts.find(a => a.accountId === this.plan.accountId())
      ?? accounts[0] ?? null;
  });

  /** The account the trade plan's orders are for, once loaded. */
  readonly planAccount = computed(() => this.accounts().find(a => a.accountId === this.plan.accountId()) ?? null);

  /**
   * True while the client has moved to another account with orders still planned for this one;
   * the tools page asks whether to go back or clear the plan.
   */
  readonly switchPending = computed(() => {
    const account = this.account();
    const planAccount = this.planAccount();
    return !this.plan.isEmpty() && !!account && !!planAccount && account.accountId !== planAccount.accountId;
  });

  /** The account's open positions, priced live, largest first. */
  readonly holdings = computed<HoldingView[]>(() => this.holdingsOf(this.account()?.accountId ?? null));

  /** The trade plan account's open positions, priced live. */
  readonly planHoldings = computed<HoldingView[]>(() => this.holdingsOf(this.plan.accountId()));

  /** The account's value split into its largest holdings, everything else, and cash. */
  readonly allocation = computed<AllocationSlice[]>(() => {
    const account = this.account();
    return account
      ? allocationSlices(this.holdings().map(h => ({ symbol: h.symbol, value: h.marketValue ?? 0 })), account.cash)
      : [];
  });

  /** Ask and bid offsets from the last trade, for the symbols in the trade plan. */
  readonly quoteOffsets = signal<Record<string, PlanQuoteOffset>>({});
  private readonly requestedQuotes = new Set<string>();
  /** Symbols whose quote has been asked for but not answered yet. */
  private readonly pendingQuotes = signal<ReadonlySet<string>>(new Set());

  /** Every planned order valued at its live ask or bid, against the account the plan is for. */
  readonly planProjection = computed<OrderProjection | null>(() => {
    const account = this.planAccount();
    if (!account || this.plan.isEmpty()) {
      return null;
    }
    return projectOrders({
      positions: this.planHoldings().map(h => ({ symbol: h.symbol, quantity: h.quantity })),
      prices: this.prices(),
      quoteOffsets: this.quoteOffsets(),
      cash: account.cash,
      orders: this.plan.sorted()
    });
  });

  /** Whether any planned order is still waiting on its quote or its price. */
  readonly pricesLoading = computed(() => {
    const prices = this.prices();
    const pending = this.pendingQuotes();
    return this.plan.symbols().some(symbol => pending.has(symbol) || !(prices[symbol] > 0));
  });

  /** The plan account's saved price protection in percent, or null when it's off or not loaded. */
  readonly planMaxSlippage = computed(() =>
    this.valuation.accountSummaries().find(a => a.accountId === this.plan.accountId())?.maxSlippagePercent ?? null);

  constructor() {
    // Previews and the plan are live, so keep every held and planned symbol on the price stream.
    effect(() => {
      if (!this.loading()) {
        this.stream.watch([...this.valuation.heldSymbols(), ...this.plan.symbols()]);
      }
    });

    // Name the account in the URL once it's known, and replace one that isn't the client's.
    effect(() => {
      const account = this.account();
      const param = this.accountParam();
      if (account && param !== account.accountId) {
        untracked(() => this.selectAccount(account.accountId, true));
      }
    });

    // An empty plan follows the account; one for an account the client no longer has is dropped.
    effect(() => {
      const account = this.account();
      if (!account) {
        return;
      }
      untracked(() => {
        const planFor = this.plan.accountId();
        if (planFor && !this.accounts().some(a => a.accountId === planFor)) {
          this.plan.clear();
        }
        this.plan.adopt(account.accountId);
      });
    });

    // Market orders fill at the ask or bid, so planned orders are valued at those.
    effect(() => {
      for (const symbol of this.plan.symbols()) {
        if (!this.requestedQuotes.has(symbol)) {
          this.requestedQuotes.add(symbol);
          untracked(() => this.loadQuote(symbol));
        }
      }
    });
  }

  load(): void {
    this.valuation.load();
  }

  /** Switches every tool to another account, staying on the current page. */
  selectAccount(accountId: string, replaceUrl = false): void {
    const tree = this.router.parseUrl(this.url());
    tree.queryParams = { ...tree.queryParams, [ACCOUNT_PARAM]: accountId };
    this.router.navigateByUrl(tree, { replaceUrl });
  }

  /** Answers the account-switch prompt: go back to the plan's account... */
  stayOnPlanAccount(): void {
    const accountId = this.plan.accountId();
    if (accountId) {
      this.selectAccount(accountId, true);
    }
  }

  /** ...or drop the plan and carry on with the new account. */
  clearPlanAndSwitch(): void {
    const account = this.account();
    this.plan.clear();
    if (account) {
      this.plan.adopt(account.accountId);
    }
  }

  /** The link to a tool, or to the hub, on the current account. */
  urlFor(toolId: string | null): string {
    const accountId = this.account()?.accountId;
    const tree = this.router.createUrlTree(toolId ? ['/tools', toolId] : ['/tools'],
      { queryParams: accountId ? { [ACCOUNT_PARAM]: accountId } : {} });
    return this.router.serializeUrl(tree);
  }

  private holdingsOf(accountId: string | null): HoldingView[] {
    const positions = this.valuation.holdingsResponse()?.accounts.find(a => a.accountId === accountId)?.positions;
    return accountId ? this.valuation.priceHoldings(this.valuation.openPositions(positions)) : [];
  }

  private loadQuote(symbol: string): void {
    const settle = () => this.pendingQuotes.update(pending => {
      const next = new Set(pending);
      next.delete(symbol);
      return next;
    });
    this.pendingQuotes.update(pending => new Set(pending).add(symbol));
    this.marketData.getQuote(symbol)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: quote => {
          settle();
          const last = Number(quote.lastPrice);
          const ask = Number(quote.askPrice) - last;
          const bid = Number(quote.bidPrice) - last;
          if (Number.isFinite(ask) && Number.isFinite(bid)) {
            this.quoteOffsets.update(current => ({ ...current, [symbol]: { ask, bid } }));
          }
        },
        // Without a quote the order is valued at the last trade.
        error: () => {
          settle();
          this.requestedQuotes.delete(symbol);
        }
      });
  }
}
