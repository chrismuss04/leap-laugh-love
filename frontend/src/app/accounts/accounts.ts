import { Component, OnInit, ViewChild, computed, effect, inject, signal, untracked } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { toSignal } from '@angular/core/rxjs-interop';
import { map } from 'rxjs';
import { AccountSummary } from '../services/holdings';
import { AccountValuationService } from '../services/account-valuation';
import { PriceStreamService } from '../services/price-stream';
import { AccountView } from '../dashboard/models';
import { AccountSwitcherComponent } from '../dashboard/account-switcher/account-switcher';
import { AccountDetailsComponent } from '../dashboard/account-details/account-details';
import { CashAccount, CashPanelComponent } from './cash-panel/cash-panel';
import { OpenAccountPanelComponent } from './open-account-panel/open-account-panel';
import { formatMoney } from '../shared/format';

/**
 * Managing the brokerage accounts under the client's profile: open another, and move cash between
 * them. At /accounts it lists every account; at /accounts/:accountId it is about one. Performance
 * and trading stay on the dashboard, which each account links to.
 */
@Component({
    selector: 'app-accounts',
    imports: [
        CommonModule, RouterLink, AccountSwitcherComponent, AccountDetailsComponent,
        CashPanelComponent, OpenAccountPanelComponent
    ],
    templateUrl: './accounts.html',
    styleUrl: './accounts.css',
    providers: [AccountValuationService]
})
export class AccountsComponent implements OnInit {
  @ViewChild(CashPanelComponent) cashPanel?: CashPanelComponent;

  private readonly valuation = inject(AccountValuationService);
  private readonly stream = inject(PriceStreamService);
  private readonly router = inject(Router);

  /** The account the page is about (/accounts/:accountId), or null for all accounts. */
  readonly accountId = toSignal(inject(ActivatedRoute).paramMap.pipe(map(params => params.get('accountId'))),
    { initialValue: null });

  readonly accounts = this.valuation.accounts;
  readonly loading = this.valuation.accountsLoading;
  readonly error = this.valuation.accountError;
  readonly multiAccount = this.valuation.multiAccount;

  /** What the side rail shows. */
  readonly rail = signal<'cash' | 'open'>('cash');

  readonly selectedAccount = computed(() => this.accounts().find(a => a.accountId === this.accountId()) ?? null);

  readonly totalCash = computed(() => this.accounts().reduce((sum, a) => sum + a.cash, 0));

  readonly cashAccounts = computed<CashAccount[]>(() =>
    this.accounts().map(a => ({ accountId: a.accountId, name: a.name, cash: a.cash })));

  money = formatMoney;

  constructor() {
    // Account values are live, so keep every held symbol on the price stream.
    effect(() => {
      if (!this.loading()) {
        this.stream.watch(this.valuation.heldSymbols());
      }
    }, { allowSignalWrites: true });

    // An account that isn't the client's, or has closed, falls back to the list.
    effect(() => {
      const accountId = this.accountId();
      const balance = this.valuation.balance();
      if (accountId && balance && !balance.accounts.some(a => a.accountId === accountId)) {
        untracked(() => this.router.navigate(['/accounts'], { replaceUrl: true }));
      }
    });
  }

  ngOnInit(): void {
    this.valuation.load();
  }

  load(): void {
    this.valuation.load();
  }

  percent(share: number): string {
    return `${Math.round(share * 100)}%`;
  }

  transferFrom(accountId: string): void {
    this.showCashPanel(panel => panel.startTransfer(accountId));
  }

  addCash(accountId: string): void {
    this.showCashPanel(panel => panel.startDeposit(accountId));
  }

  openNew(): void {
    this.rail.set('open');
    this.scrollToRail();
  }

  onOpened(_: AccountSummary): void {
    this.load();
  }

  /**
   * From the new-account confirmation: go to the new account. The page is rebuilt for its route,
   * and the money panel starts on the account it's about, adding cash.
   */
  fund(accountId: string): void {
    this.router.navigate(['/accounts', accountId]);
  }

  trackById(_: number, account: AccountView): string {
    return account.accountId;
  }

  private showCashPanel(start: (panel: CashPanelComponent) => void): void {
    this.rail.set('cash');
    // Let the panel render (it may have been swapped out for the open-account panel) first.
    setTimeout(() => {
      if (this.cashPanel) {
        start(this.cashPanel);
      }
      this.scrollToRail();
    });
  }

  private scrollToRail(): void {
    document.getElementById('accounts-rail')?.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
  }
}
