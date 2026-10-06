import { Component, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ShellComponent } from '../shell/shell';
import { countryName } from '../shared/countries';

/** The signed-in client's account details. Reads the profile the shell already loaded instead of fetching it again. */
@Component({
    selector: 'app-profile',
    imports: [CommonModule],
    template: `
    <div class="page"><div class="page-container">
      <div class="page-header">
        <p class="eyebrow">Account</p>
        <h1 class="font-display headline-page"><em>Profile</em></h1>
      </div>
      <dl *ngIf="shell.profile() as p; else loading">
        <dt>Name</dt><dd>{{ p.fullName }}</dd>
        <dt>Email</dt><dd>{{ p.email }}</dd>
        <dt>Username</dt><dd>{{ p.email }}</dd>
        <dt>Phone</dt><dd>{{ p.phone || '—' }}</dd>
        <dt>Address</dt>
        <dd>
          {{ p.addressLine1 }}<ng-container *ngIf="p.addressLine2">, {{ p.addressLine2 }}</ng-container><br />
          {{ p.city }}<ng-container *ngIf="p.stateRegion">, {{ p.stateRegion }}</ng-container> {{ p.postalCode }}<br />
          {{ countryName(p.countryCode) }}
        </dd>
        <dt>Account type</dt><dd>{{ shell.experienceLabel() }}</dd>
        <dt>Account created</dt><dd>{{ p.createdAt | date: 'longDate' }}</dd>
      </dl>
      <ng-template #loading>
        <p *ngIf="shell.profileError(); else spinner" class="error-message">Couldn't load your profile. Please refresh the page.</p>
        <ng-template #spinner><p class="loading">Loading profile...</p></ng-template>
      </ng-template>
    </div></div>`,
    styles: `
    @import '../shared/page-shell.css';
    .page-container { max-width: 640px; }
    dl { display: grid; grid-template-columns: max-content 1fr; gap: 16px 32px; margin: 0; }
    dt { color: var(--muted-foreground); font-size: 14px; }
    dd { margin: 0; color: var(--foreground); }`
})
export class ProfileComponent {
  readonly shell = inject(ShellComponent);
  readonly countryName = countryName;
}
