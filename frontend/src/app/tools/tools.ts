import { Component, OnInit, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterOutlet } from '@angular/router';
import { AccountValuationService } from '../services/account-valuation';
import { AccountSwitcherComponent } from '../dashboard/account-switcher/account-switcher';
import { ToolsContext } from './tools-context';
import { ACCOUNT_PARAM } from './tools-catalog';

/**
 * Trading Tools: the hub at /tools and each tool at /tools/:tool. This page owns the account the
 * tools act on, so the hub's tiles and an open tool render into its outlet and share one load.
 */
@Component({
    selector: 'app-tools',
    imports: [CommonModule, RouterOutlet, AccountSwitcherComponent],
    template: `
    <div class="tools-page">
      <app-account-switcher *ngIf="context.multiAccount()" class="scope" [showAll]="false"
                            [basePath]="context.path()" [queryParam]="accountParam"
                            [accounts]="context.accounts()" [selected]="context.account()?.accountId ?? null"
                            [loading]="context.loading() && !context.error()"></app-account-switcher>

      <div *ngIf="context.error()" class="banner" role="alert">
        <span>{{ context.error() }}</span>
        <button type="button" (click)="context.load()">Try again</button>
      </div>

      <router-outlet></router-outlet>
    </div>
  `,
    styleUrl: './tools.css',
    providers: [AccountValuationService, ToolsContext]
})
export class ToolsComponent implements OnInit {
  readonly context = inject(ToolsContext);
  readonly accountParam = ACCOUNT_PARAM;

  ngOnInit(): void {
    this.context.load();
  }
}
