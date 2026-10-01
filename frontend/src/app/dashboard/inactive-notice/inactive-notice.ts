import { Component, computed, input } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterLink } from '@angular/router';
import { AccountView } from '../models';

/** Points the client at accounts the nightly job flagged as inactive. */
@Component({
    selector: 'app-inactive-notice',
    imports: [CommonModule, RouterLink],
    template: `
    <a *ngIf="inactive()[0] as first" class="notice" role="status" [routerLink]="['/dashboard', first.accountId]">
      <span *ngIf="inactive().length === 1; else many">
        {{ first.name }} needs attention: no balance since {{ first.inactiveSince | date: 'MMM d, y' }}.
      </span>
      <ng-template #many><span>{{ inactive().length }} accounts need attention.</span></ng-template>
      <span aria-hidden="true">›</span>
    </a>`,
    styles: `
    .notice { display: flex; justify-content: space-between; gap: 12px; padding: 12px 16px;
      border-radius: var(--radius-md); background: var(--warning-muted); color: var(--warning);
      font-size: 14px; text-decoration: none; }`
})
export class InactiveNoticeComponent {
  readonly accounts = input<AccountView[]>([]);
  readonly inactive = computed(() => this.accounts().filter(a => a.inactiveSince));
}
