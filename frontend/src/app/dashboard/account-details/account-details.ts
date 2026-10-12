import { Component, Input } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterLink } from '@angular/router';
import { AccountView } from '../models';
import { formatMoney } from '../../shared/format';

/** Facts about one account that don't change tick to tick: number, status, and where its value sits. */
@Component({
    selector: 'app-account-details',
    imports: [CommonModule, RouterLink],
    template: `
    <section class="card" aria-labelledby="account-details-title">
      <header class="card-header">
        <h2 id="account-details-title">Account details</h2>
        <span *ngIf="account" class="status" [class.active]="account.status === 'ACTIVE'">
          {{ (account.status ?? 'Unknown') | titlecase }}
        </span>
      </header>

      <dl *ngIf="account; else loadingRows" class="rows fade-in">
        <div class="row">
          <dt>Account number</dt>
          <dd class="num">{{ account.accountNumber }}</dd>
        </div>
        <div class="row">
          <dt>Opened</dt>
          <dd>{{ account.openedAt ? (account.openedAt | date: 'MMM d, y') : '—' }}</dd>
        </div>
        <div *ngIf="account.inactiveSince" class="row">
          <dt>Inactive since</dt>
          <dd>{{ account.inactiveSince | date: 'MMM d, y' }} · deposit funds to reactivate</dd>
        </div>
        <div class="row">
          <dt>Trading</dt>
          <dd>{{ account.tradingEnabled === null ? '—' : account.tradingEnabled ? 'Enabled' : 'Restricted' }}</dd>
        </div>
        <div class="row">
          <dt>Share of total value</dt>
          <dd class="num">{{ (account.share * 100) | number: '1.0-1' }}%</dd>
        </div>
      </dl>
      <ng-template #loadingRows>
        <div class="rows" aria-busy="true">
          <div class="row" *ngFor="let _ of [1, 2, 3, 4]"><span class="skeleton" style="width: 100%; height: 18px"></span></div>
        </div>
      </ng-template>

      <div *ngIf="account" class="split fade-in" role="img"
           [attr.aria-label]="'Cash ' + money(account.cash) + ', investments ' + money(account.marketValue)">
        <span class="bar">
          <span class="segment invested" [style.flex-grow]="account.marketValue"></span>
          <span class="segment cash" [style.flex-grow]="account.cash"></span>
        </span>
        <span class="legend">
          <span><i class="key invested"></i>Investments <b class="num">{{ money(account.marketValue) }}</b></span>
          <span><i class="key cash"></i>Cash <b class="num">{{ money(account.cash) }}</b></span>
        </span>
      </div>

      <a *ngIf="account && showManage" class="manage" [routerLink]="['/accounts', account.accountId]">
        Manage account &amp; transfers <span aria-hidden="true">›</span>
      </a>
    </section>
  `,
    styles: [`
    .card { background: var(--surface); border: 1px solid var(--border); border-radius: var(--radius-lg); }
    .card-header { display: flex; align-items: center; justify-content: space-between; padding: 18px 20px 10px; }
    h2 { font-size: 16px; font-weight: 600; }
    .status {
      font-size: 11px; font-weight: 600; padding: 3px 8px; border-radius: 999px;
      background: var(--muted); color: var(--muted-foreground);
    }
    .status.active { background: var(--gain-muted); color: var(--gain); }
    .rows { display: flex; flex-direction: column; }
    .row {
      display: flex; justify-content: space-between; gap: 16px;
      padding: 10px 20px; border-top: 1px solid var(--border); font-size: 13px;
    }
    dt { color: var(--muted-foreground); }
    dd { font-weight: 500; text-align: right; }
    .split { padding: 14px 20px 18px; border-top: 1px solid var(--border); display: flex; flex-direction: column; gap: 10px; }
    .bar { display: flex; gap: 3px; height: 8px; border-radius: 999px; overflow: hidden; background: var(--muted); }
    .segment { flex-basis: 0; }
    .invested, .key.invested { background: var(--primary); }
    .cash, .key.cash { background: var(--muted-foreground); }
    .legend { display: flex; flex-wrap: wrap; justify-content: space-between; gap: 6px 16px; font-size: 12px; color: var(--muted-foreground); }
    .legend span { display: inline-flex; align-items: center; gap: 6px; }
    .legend b { color: var(--foreground); font-weight: 600; }
    .key { width: 8px; height: 8px; border-radius: 2px; display: inline-block; }
    .manage {
      display: flex; justify-content: space-between; align-items: center;
      padding: 12px 20px; border-top: 1px solid var(--border);
      font-size: 13px; font-weight: 600; color: var(--primary); text-decoration: none;
      transition: background 0.15s ease;
    }
    .manage:hover { background: var(--surface-hover); border-radius: 0 0 var(--radius-lg) var(--radius-lg); }
  `]
})
export class AccountDetailsComponent {
  @Input() account: AccountView | null = null;
  /** Off on the Accounts page itself, where the link would lead back to the same page. */
  @Input() showManage = true;

  money = formatMoney;
}
