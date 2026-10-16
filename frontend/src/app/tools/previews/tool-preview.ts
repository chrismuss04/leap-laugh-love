import { Component, Input, computed, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { SparklineComponent } from '../../dashboard/sparkline/sparkline';
import { formatMoney } from '../../shared/format';
import { ToolsContext } from '../tools-context';
import { ToolId } from '../tools-catalog';
import { MiniDonutComponent } from './mini-donut';

/**
 * What a tool would work with, drawn small from the selected account's live data: its allocation,
 * its cash, an empty basket, or its value today. Shows a skeleton while the account loads and the
 * tool's plain icon if it couldn't.
 */
@Component({
    selector: 'app-tool-preview',
    imports: [CommonModule, MiniDonutComponent, SparklineComponent],
    template: `
    <div class="preview" [attr.data-state]="state()" [class.large]="large">
      <ng-container *ngIf="tool !== 'basket'; else basket" [ngSwitch]="state()">
        <span *ngSwitchCase="'loading'" class="skeleton" [class.round]="tool === 'rebalance'"
              data-testid="preview-skeleton" aria-hidden="true"></span>

        <span *ngSwitchCase="'error'" class="icon" data-testid="preview-icon" aria-hidden="true">
          <ng-container *ngTemplateOutlet="iconTemplate"></ng-container>
        </span>

        <ng-container *ngSwitchDefault [ngSwitch]="tool">
          <app-mini-donut *ngSwitchCase="'rebalance'" class="fade-in" [slices]="context.allocation()"
                          [size]="large ? 160 : 88"></app-mini-donut>

          <div *ngSwitchCase="'raise-cash'" class="cash fade-in">
            <span class="cash-label">Cash available</span>
            <span class="cash-value num" data-testid="preview-cash">{{ money(context.account()?.cash) }}</span>
            <span class="cash-bar" aria-hidden="true"><span [style.width.%]="cashShare() * 100"></span></span>
          </div>

          <app-sparkline *ngSwitchCase="'draw'" class="fade-in" [values]="series()"
                         [baseline]="context.account()?.previousCloseValue ?? null"
                         [width]="large ? 320 : 150" [height]="large ? 96 : 56"></app-sparkline>
        </ng-container>
      </ng-container>

    </div>

    <!-- The basket starts empty for everyone, so it needs no data to draw. -->
    <ng-template #basket>
      <span class="icon basket fade-in" data-testid="preview-basket" aria-hidden="true">
        <ng-container *ngTemplateOutlet="iconTemplate"></ng-container>
      </span>
    </ng-template>

    <ng-template #iconTemplate>
      <svg viewBox="0 0 48 48" width="48" height="48" fill="none" stroke="currentColor" stroke-width="2.5"
           stroke-linecap="round" stroke-linejoin="round" [ngSwitch]="tool">
        <g *ngSwitchCase="'rebalance'"><circle cx="24" cy="24" r="16" /><path d="M24 8v16l11 11" /></g>
        <g *ngSwitchCase="'raise-cash'"><rect x="6" y="14" width="36" height="22" rx="3" /><circle cx="24" cy="25" r="5" /></g>
        <g *ngSwitchCase="'basket'">
          <path d="M8 19h32l-4 19H12z" stroke-dasharray="3 4" />
          <path d="M16 19l6-10M32 19l-6-10" />
        </g>
        <g *ngSwitchCase="'draw'"><path d="M6 38l10-12 8 6 18-20" /><path d="M36 12h6v6" /></g>
      </svg>
    </ng-template>
  `,
    styles: [`
    :host { display: block; }
    .preview {
      display: flex;
      align-items: center;
      justify-content: center;
      height: 104px;
    }
    .preview.large { height: 180px; }
    .skeleton { width: 70%; height: 56px; }
    .skeleton.round { width: 88px; height: 88px; border-radius: 50%; }
    .icon { color: var(--muted-foreground); display: inline-flex; }
    .basket { color: color-mix(in srgb, var(--primary) 70%, var(--muted-foreground)); }
    .large .icon svg { width: 72px; height: 72px; }
    .cash { display: flex; flex-direction: column; gap: 6px; width: min(100%, 200px); }
    .cash-label { font-size: 12px; color: var(--muted-foreground); }
    .cash-value { font-size: 24px; font-weight: 600; color: var(--foreground); }
    .large .cash-value { font-size: 36px; }
    .cash-bar { height: 4px; border-radius: 999px; background: var(--muted); overflow: hidden; }
    .cash-bar span { display: block; height: 100%; background: var(--primary); transition: width 0.6s ease; }
    @media (prefers-reduced-motion: reduce) { .cash-bar span { transition: none; } }
  `]
})
export class ToolPreviewComponent {
  @Input({ required: true }) tool!: ToolId;
  @Input() large = false;

  readonly context = inject(ToolsContext);

  readonly state = computed<'loading' | 'error' | 'ready'>(() => {
    if (this.context.error()) {
      return 'error';
    }
    return this.context.loading() || !this.context.account() ? 'loading' : 'ready';
  });

  readonly cashShare = computed(() => {
    const account = this.context.account();
    return account && account.value > 0 ? Math.min(account.cash / account.value, 1) : 0;
  });

  /** Today's account value, ending at its live value; flat when it holds nothing to chart. */
  readonly series = computed(() => {
    const account = this.context.account();
    if (!account) {
      return [];
    }
    return account.intraday.length >= 2 ? [...account.intraday, account.value] : [account.value, account.value];
  });

  money = formatMoney;
}
