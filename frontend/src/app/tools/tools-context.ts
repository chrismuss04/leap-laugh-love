import { Injectable, computed, effect, inject, untracked } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { NavigationEnd, Router } from '@angular/router';
import { filter, map } from 'rxjs';
import { AccountValuationService } from '../services/account-valuation';
import { PriceStreamService } from '../services/price-stream';
import { AccountView, HoldingView } from '../dashboard/models';
import { ACCOUNT_PARAM } from './tools-catalog';

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

/**
 * Trading Tools: the account every tool acts on, valued live. Provided by the tools page, so the
 * hub and each tool share one load and one account as the client moves between them. The account
 * lives in the URL (?account=<id>) so a tool's link opens on the same account.
 */
@Injectable()
export class ToolsContext {
  private readonly valuation = inject(AccountValuationService);
  private readonly stream = inject(PriceStreamService);
  private readonly router = inject(Router);

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

  /** The account in the URL, or the client's first account until the URL names one. */
  readonly account = computed<AccountView | null>(() => {
    const accounts = this.accounts();
    return accounts.find(a => a.accountId === this.accountParam()) ?? accounts[0] ?? null;
  });

  /** The account's open positions, priced live, largest first. */
  readonly holdings = computed<HoldingView[]>(() => {
    const account = this.account();
    const positions = this.valuation.holdingsResponse()?.accounts.find(a => a.accountId === account?.accountId)?.positions;
    return account ? this.valuation.priceHoldings(this.valuation.openPositions(positions)) : [];
  });

  /** The account's value split into its largest holdings, everything else, and cash. */
  readonly allocation = computed<AllocationSlice[]>(() => {
    const account = this.account();
    if (!account) {
      return [];
    }
    const priced = this.holdings().filter(h => (h.marketValue ?? 0) > 0);
    const top = priced.slice(0, MAX_SLICES);
    const rest = priced.slice(MAX_SLICES).reduce((sum, h) => sum + h.marketValue!, 0);
    const slices = top.map((h, i) => ({ key: h.symbol, label: h.symbol, value: h.marketValue!, color: SLICE_COLORS[i] }));
    if (rest > 0) {
      slices.push({ key: 'other', label: 'Other', value: rest, color: OTHER_COLOR });
    }
    if (account.cash > 0) {
      slices.push({ key: 'cash', label: 'Cash', value: account.cash, color: CASH_COLOR });
    }
    const total = slices.reduce((sum, s) => sum + s.value, 0);
    return slices.map(s => ({ ...s, share: total ? s.value / total : 0 }));
  });

  constructor() {
    // Previews are live, so keep every held symbol on the price stream.
    effect(() => {
      if (!this.loading()) {
        this.stream.watch(this.valuation.heldSymbols());
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

  /** The link to a tool, or to the hub, on the current account. */
  urlFor(toolId: string | null): string {
    const accountId = this.account()?.accountId;
    const tree = this.router.createUrlTree(toolId ? ['/tools', toolId] : ['/tools'],
      { queryParams: accountId ? { [ACCOUNT_PARAM]: accountId } : {} });
    return this.router.serializeUrl(tree);
  }
}
