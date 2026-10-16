import { Component, Input } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterLink } from '@angular/router';
import { AccountView } from '../models';

/**
 * Tabs for scoping a page: every account combined, or one account. Each tab is a link, so an
 * account view has its own URL and the browser's back button returns to the overview. Used by the
 * dashboard (/dashboard/:accountId) and the Accounts page (/accounts/:accountId). Trading Tools keeps
 * its tool in the path, so it names the account in a query parameter instead (/tools/rebalance?account=a1).
 */
@Component({
    selector: 'app-account-switcher',
    imports: [CommonModule, RouterLink],
    template: `
    <nav class="switcher" aria-label="Accounts">
      <a *ngIf="showAll" class="tab" [routerLink]="basePath" [class.on]="selected === null"
         [attr.aria-current]="selected === null ? 'page' : null">
        All accounts
      </a>
      <ng-container *ngIf="!loading">
        <a *ngFor="let account of accounts; trackBy: trackById" class="tab fade-in"
           [routerLink]="queryParam ? basePath : [basePath, account.accountId]"
           [queryParams]="queryParamsFor(account)" [queryParamsHandling]="queryParam ? 'merge' : ''"
           [class.on]="account.accountId === selected"
           [attr.aria-current]="account.accountId === selected ? 'page' : null"
           [attr.aria-label]="account.name">
          <span class="dot" [style.background]="account.color" aria-hidden="true"></span>
          {{ account.name }}
          <span *ngIf="account.inactiveSince" class="badge-inactive">Inactive</span>
        </a>
      </ng-container>
      <ng-container *ngIf="loading">
        <span class="skeleton tab-skeleton" *ngFor="let _ of [1, 2]"></span>
      </ng-container>
    </nav>
  `,
    styles: [`
    .switcher {
      display: flex;
      gap: 6px;
      overflow-x: auto;
      scrollbar-width: none;
      margin: 0 -2px;
      padding: 2px;
    }
    .switcher::-webkit-scrollbar { display: none; }
    .tab {
      display: inline-flex;
      align-items: center;
      gap: 8px;
      flex-shrink: 0;
      padding: 7px 14px;
      border-radius: 999px;
      border: 1px solid var(--border);
      color: var(--muted-foreground);
      font-size: 13px;
      font-weight: 600;
      text-decoration: none;
      white-space: nowrap;
      transition: color 0.15s ease, background 0.15s ease, border-color 0.15s ease;
    }
    .tab:hover { color: var(--foreground); background: var(--surface-hover); }
    .tab.on {
      color: var(--foreground);
      background: var(--muted);
      border-color: color-mix(in srgb, var(--foreground) 22%, var(--border));
    }
    .dot { width: 8px; height: 8px; border-radius: 50%; }
    .tab-skeleton { width: 132px; height: 34px; border-radius: 999px; flex-shrink: 0; }
  `]
})
export class AccountSwitcherComponent {
  @Input() accounts: AccountView[] = [];
  @Input() selected: string | null = null;
  @Input() loading = false;
  /** Route of the all-accounts view; an account's tab links to basePath/:accountId. */
  @Input() basePath = '/dashboard';
  /** Names the account in this query parameter on basePath, rather than as a path segment. */
  @Input() queryParam: string | null = null;
  /** Whether to offer the all-accounts view; off where a page always acts on one account. */
  @Input() showAll = true;

  queryParamsFor(account: AccountView): Record<string, string> | null {
    return this.queryParam ? { [this.queryParam]: account.accountId } : null;
  }

  trackById(_: number, account: AccountView): string {
    return account.accountId;
  }
}
