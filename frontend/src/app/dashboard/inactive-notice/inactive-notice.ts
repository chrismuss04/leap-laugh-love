import { Component, computed, input, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterLink } from '@angular/router';
import { AccountView } from '../models';

/** Points the client at accounts the nightly job flagged as inactive. */
@Component({
    selector: 'app-inactive-notice',
    imports: [CommonModule, RouterLink],
    template: `
    <div *ngIf="!dismissed() && inactive()[0] as first" class="notice" role="status">
      <a [routerLink]="['/dashboard', first.accountId]">
        <ng-container *ngIf="inactive().length === 1; else many">
          {{ first.name }} needs attention: no balance since {{ first.inactiveSince | date: 'MMM d, y' }}.
        </ng-container>
        <ng-template #many>{{ inactive().length }} accounts need attention.</ng-template>
        <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5"
             stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="m9 6 6 6-6 6" /></svg>
      </a>
      <button type="button" aria-label="Dismiss" (click)="dismissed.set(true)">×</button>
    </div>`,
    styles: `
    .notice { position: relative; display: flex; padding: 12px 36px 12px 16px; border-radius: var(--radius-md);
      background: var(--warning-muted); color: var(--warning); font-size: 14px; }
    a { flex: 1; display: flex; justify-content: space-between; align-items: center; gap: 12px; color: inherit; text-decoration: none; }
    svg { flex-shrink: 0; }
    button { position: absolute; top: 4px; right: 6px; padding: 2px; background: none; border: 0; color: inherit;
      font-size: 16px; line-height: 1; cursor: pointer; }`
})
export class InactiveNoticeComponent {
  readonly accounts = input<AccountView[]>([]);
  readonly inactive = computed(() => this.accounts().filter(a => a.inactiveSince));
  readonly dismissed = signal(false);
}
