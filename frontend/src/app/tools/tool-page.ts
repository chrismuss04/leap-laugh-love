import { Component, computed, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Router } from '@angular/router';
import { map } from 'rxjs';
import { ToolPreviewComponent } from './previews/tool-preview';
import { RaiseCashToolComponent } from './raise-cash/raise-cash-tool';
import { RebalanceToolComponent } from './rebalance/rebalance-tool';
import { BasketToolComponent } from './basket/basket-tool';
import { ToolsContext } from './tools-context';
import { findTool, isModifiedClick } from './tools-catalog';

/**
 * Trading Tools: one tool at /tools/:tool, on the account the hub had selected. The route only
 * matches known tools (see TOOL_ROUTES). Each tool's workspace arrives in its own story; until
 * then the page shows its live preview at full size. Rebalance Donut, Raise Cash and Basket Builder have their workspaces.
 */
@Component({
    selector: 'app-tool-page',
    imports: [CommonModule, ToolPreviewComponent, RebalanceToolComponent, RaiseCashToolComponent, BasketToolComponent],
    template: `
    <section *ngIf="tool() as tool" class="tool-page" [attr.data-tool]="tool.id" aria-labelledby="tool-title">
      <a class="back" [attr.href]="context.urlFor(null)" (click)="back($event)">
        <span aria-hidden="true">‹</span> All tools
      </a>
      <h1 id="tool-title">{{ tool.name }}</h1>
      <p class="blurb">{{ tool.blurb }}</p>
      <ng-container [ngSwitch]="tool.id">
        <app-rebalance-tool *ngSwitchCase="'rebalance'"></app-rebalance-tool>
        <app-raise-cash-tool *ngSwitchCase="'raise-cash'"></app-raise-cash-tool>
        <app-basket-tool *ngSwitchCase="'basket'"></app-basket-tool>
        <ng-container *ngSwitchDefault [ngTemplateOutlet]="soon"></ng-container>
      </ng-container>
      <ng-template #soon>
        <app-tool-preview [tool]="tool.id" [large]="true"></app-tool-preview>
        <p class="soon">This tool's workspace is on its way.</p>
      </ng-template>
    </section>
  `,
    styles: [`
    :host { display: block; }
    .tool-page {
      /* Pairs with the opened tile; see ToolsHubComponent.open. */
      view-transition-name: tool-expand;
      padding: 20px 24px 28px;
      border-radius: var(--radius-lg);
      border: 1px solid var(--border);
      background: var(--surface);
    }
    .back { display: inline-flex; gap: 6px; font-size: 13px; font-weight: 600; color: var(--muted-foreground); text-decoration: none; }
    .back:hover, .back:focus-visible { color: var(--foreground); }
    h1 { margin-top: 12px; font-size: 26px; font-weight: 600; color: var(--foreground); }
    .blurb { margin-top: 6px; font-size: 14px; color: var(--muted-foreground); }
    app-tool-preview, app-rebalance-tool, app-raise-cash-tool, app-basket-tool { margin-top: 20px; }
    .soon { margin-top: 12px; text-align: center; font-size: 13px; color: var(--muted-foreground); }
    @media (max-width: 640px) { .tool-page { padding: 16px; } }
  `]
})
export class ToolPageComponent {
  readonly context = inject(ToolsContext);
  private readonly router = inject(Router);
  readonly toolId = toSignal(inject(ActivatedRoute).paramMap.pipe(map(params => params.get('tool'))),
    { initialValue: null });
  readonly tool = computed(() => findTool(this.toolId()));

  /** Back to the hub through the router, so the page doesn't reload. */
  back(event: MouseEvent): void {
    if (isModifiedClick(event)) {
      return;
    }
    event.preventDefault();
    this.router.navigateByUrl(this.context.urlFor(null));
  }
}
