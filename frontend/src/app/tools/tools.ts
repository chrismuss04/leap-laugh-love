import { Component, ElementRef, OnInit, ViewChild, effect, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterOutlet } from '@angular/router';
import { AccountValuationService } from '../services/account-valuation';
import { AccountSwitcherComponent } from '../dashboard/account-switcher/account-switcher';
import { ToolsContext } from './tools-context';
import { ACCOUNT_PARAM } from './tools-catalog';
import { TradePlanService } from './trade-plan';
import { TradePlanTrayComponent } from './trade-plan-tray';
import { PlanReviewComponent } from './plan-review';
import { PlanSubmission } from './plan-submission';

/**
 * Trading Tools: the hub at /tools and each tool at /tools/:tool. This page owns the account the
 * tools act on and the trade plan they add to, so the hub's tiles and an open tool render into its
 * outlet and share one load, one account and one tray.
 */
@Component({
    selector: 'app-tools',
    imports: [CommonModule, RouterOutlet, AccountSwitcherComponent, TradePlanTrayComponent, PlanReviewComponent],
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

    <app-trade-plan-tray (review)="reviewOpen.set(true)"></app-trade-plan-tray>

    <app-plan-review *ngIf="reviewOpen()" (closed)="closeReview()"></app-plan-review>

    <!-- The plan belongs to one account, so moving to another asks what to do with it - once the
         review sheet, which is about the plan's own account, has closed. -->
    <div *ngIf="context.switchPending() && !reviewOpen()" class="backdrop">
      <div #switchDialog class="dialog" role="alertdialog" aria-modal="true" aria-labelledby="switch-title"
           aria-describedby="switch-body" (keydown.escape)="context.stayOnPlanAccount()" data-testid="switch-dialog">
        <h2 id="switch-title">Keep your trade plan?</h2>
        <p id="switch-body">
          Your plan has {{ context.plan.count() }} order{{ context.plan.count() === 1 ? '' : 's' }} for
          {{ context.planAccount()?.name }}. Orders can't move to {{ context.account()?.name }}.
        </p>
        <div class="actions">
          <button type="button" class="primary" (click)="context.stayOnPlanAccount()" data-testid="stay">
            Stay on {{ context.planAccount()?.name }}
          </button>
          <button type="button" (click)="context.clearPlanAndSwitch()" data-testid="clear-and-switch">
            Clear plan and switch
          </button>
        </div>
      </div>
    </div>
  `,
    styleUrl: './tools.css',
    providers: [AccountValuationService, TradePlanService, ToolsContext, PlanSubmission]
})
export class ToolsComponent implements OnInit {
  @ViewChild('switchDialog') private switchDialog?: ElementRef<HTMLElement>;

  readonly context = inject(ToolsContext);
  readonly accountParam = ACCOUNT_PARAM;
  private readonly submission = inject(PlanSubmission);

  /** Whether the review-and-submit sheet is up. */
  readonly reviewOpen = signal(false);

  constructor() {
    // Put focus in the prompt as it opens, so a keyboard user answers it before anything else.
    effect(() => {
      if (this.context.switchPending() && !this.reviewOpen()) {
        queueMicrotask(() => this.switchDialog?.nativeElement.querySelector<HTMLButtonElement>('button')?.focus());
      }
    });
  }

  ngOnInit(): void {
    this.context.load();
  }

  closeReview(): void {
    if (!this.submission.busy()) {
      this.reviewOpen.set(false);
    }
  }

  /** Leaving the tools mid-submission would hide orders still being sent, so it waits until they finish. */
  canLeave(): boolean {
    return !this.submission.busy();
  }
}
