import { Component, Input } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterLink } from '@angular/router';
import { AccountView } from '../models';
import { SparklineComponent } from '../sparkline/sparkline';
import { RollingNumberComponent } from '../../shared/rolling-number';
import { direction, formatSignedMoney, formatSignedPercent } from '../../shared/format';

/**
 * Every account at a glance: how the combined value splits between them, and each account's value
 * and move today. A row opens that account's dashboard; opening, funding and moving cash between
 * accounts is on the Accounts page, linked from the header.
 */
@Component({
    selector: 'app-accounts-overview',
    imports: [CommonModule, RouterLink, SparklineComponent, RollingNumberComponent],
    template: `
    <section class="card" aria-labelledby="accounts-title">
      <header class="card-header">
        <h2 id="accounts-title">Accounts</h2>
        <span class="header-end">
          <span class="meta" *ngIf="!loading">{{ accounts.length }} {{ accounts.length === 1 ? 'account' : 'accounts' }}</span>
          <a routerLink="/accounts" class="link">Manage</a>
        </span>
      </header>

      <div *ngIf="loading" aria-busy="true">
        <div class="allocation"><span class="skeleton" style="display: block; height: 8px"></span></div>
        <div class="row skeleton-row" *ngFor="let _ of [1, 2]">
          <span class="skeleton" style="width: 140px; height: 32px"></span>
          <span class="skeleton" style="width: 120px; height: 32px; margin-left: auto"></span>
        </div>
      </div>

      <p *ngIf="!loading && accounts.length === 0" class="empty fade-in">You don't have any open accounts.</p>

      <ng-container *ngIf="!loading && accounts.length > 0">
        <div class="allocation fade-in" role="img" [attr.aria-label]="allocationLabel()">
          <span class="bar">
            <span *ngFor="let account of accounts; trackBy: trackById" class="segment"
                  [style.flex-grow]="account.share" [style.background]="account.color"></span>
          </span>
        </div>

        <ul class="rows fade-in">
          <li *ngFor="let account of accounts; trackBy: trackById">
            <a class="row" [routerLink]="['/dashboard', account.accountId]" data-testid="account-row">
              <span class="identity">
                <span class="name">
                  <span class="dot" [style.background]="account.color" aria-hidden="true"></span>
                  {{ account.name }}
                </span>
                <span class="sub">
                  {{ account.positionCount }} {{ account.positionCount === 1 ? 'position' : 'positions' }}<span
                  class="wide"> · {{ percent(account.share) }} of total</span>
                </span>
              </span>

              <app-sparkline class="spark" [values]="account.intraday" [baseline]="account.previousCloseValue"></app-sparkline>

              <span class="value-col">
                <span class="value num" [appRollingNumber]="account.value"></span>
                <span class="sub num" [ngClass]="'text-' + dir(account.dayChange)">
                  <span class="wide">{{ signedMoney(account.dayChange) }} (</span>{{ signedPercent(account.dayChangePercent) }}<span
                  class="wide">)</span> today
                </span>
              </span>

              <span class="chevron" aria-hidden="true">›</span>
            </a>
          </li>
        </ul>
      </ng-container>
    </section>
  `,
    styles: [`
    .card { background: var(--surface); border: 1px solid var(--border); border-radius: var(--radius-lg); }
    .card-header { display: flex; align-items: baseline; justify-content: space-between; padding: 18px 20px 10px; }
    h2 { font-size: 16px; font-weight: 600; }
    .meta { font-size: 13px; color: var(--muted-foreground); }
    .header-end { display: flex; align-items: baseline; gap: 12px; }
    .link { font-size: 13px; font-weight: 600; color: var(--primary); text-decoration: none; }
    .link:hover { text-decoration: underline; }

    .allocation { padding: 4px 20px 16px; }
    .bar { display: flex; gap: 3px; height: 8px; border-radius: 999px; overflow: hidden; }
    .segment { flex-basis: 0; min-width: 4px; }

    .rows { list-style: none; }
    .row {
      display: grid;
      grid-template-columns: minmax(0, 1fr) 88px minmax(150px, auto) 12px;
      align-items: center;
      gap: 16px;
      padding: 14px 16px 14px 20px;
      border-top: 1px solid var(--border);
      color: inherit;
      text-decoration: none;
      transition: background 0.15s ease;
    }
    .row:hover { background: var(--surface-hover); }
    .row:hover .chevron { color: var(--foreground); transform: translateX(2px); }
    .skeleton-row { display: flex; }

    .identity, .value-col { display: flex; flex-direction: column; gap: 2px; min-width: 0; }
    .value-col { align-items: flex-end; }
    .name { display: flex; align-items: center; gap: 8px; font-weight: 600; font-size: 15px; white-space: nowrap; }
    .dot { width: 8px; height: 8px; border-radius: 50%; flex-shrink: 0; }
    .sub { font-size: 12px; color: var(--muted-foreground); white-space: nowrap; }
    .identity .sub { padding-left: 16px; }
    .sub.text-gain { color: var(--gain); }
    .sub.text-loss { color: var(--loss); }
    .value { font-size: 15px; font-weight: 500; padding: 0 2px; border-radius: 4px; }
    .chevron { font-size: 20px; line-height: 1; color: var(--muted-foreground); transition: color 0.15s ease, transform 0.15s ease; }

    .empty { padding: 28px 20px 32px; border-top: 1px solid var(--border); text-align: center; color: var(--muted-foreground); font-size: 14px; }

    @media (max-width: 640px) {
      .row { grid-template-columns: minmax(0, 1fr) auto 12px; gap: 12px; padding-left: 16px; }
      .spark, .wide { display: none; }
    }
  `]
})
export class AccountsOverviewComponent {
  @Input() accounts: AccountView[] = [];
  @Input() loading = false;

  signedMoney = formatSignedMoney;
  signedPercent = formatSignedPercent;
  dir = direction;

  percent(share: number): string {
    return `${Math.round(share * 100)}%`;
  }

  allocationLabel(): string {
    return 'Allocation across accounts: ' + this.accounts.map(a => `${a.name} ${this.percent(a.share)}`).join(', ');
  }

  trackById(_: number, account: AccountView): string {
    return account.accountId;
  }
}
