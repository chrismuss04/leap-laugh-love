import { Component, computed, effect, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { Router } from '@angular/router';
import { ToolsContext } from '../tools-context';
import { Allocation, buildAllocation } from './allocation';
import { AllocationDonutComponent } from './allocation-donut';
import { TargetAllocation } from './target-allocation';
import { TargetTableComponent } from './target-table';

/**
 * Rebalance Donut's workspace: the selected account's current allocation, priced live, with a
 * target allocation the client shapes by dragging the donut's outer ring or typing into the table
 * under it. Turning targets into trades comes in a later story.
 */
@Component({
  selector: 'app-rebalance-tool',
  imports: [CommonModule, AllocationDonutComponent, TargetTableComponent],
  template: `
    <div *ngIf="editable()" class="hint" data-testid="rebalance-hint">
      <span class="key"><span class="ring now" aria-hidden="true"></span>Inner ring: now</span>
      <span class="key"><span class="ring target" aria-hidden="true"></span>Outer ring: target</span>
      <span class="how">Drag a <span class="knob" aria-hidden="true"></span> handle to reshape. Hold Shift for 0.1% steps.</span>
    </div>

    <app-allocation-donut [allocation]="allocation()" [state]="state()" [errorMessage]="context.error()"
                          [targets]="editable() ? targets : null"
                          (retry)="context.load()" (addHolding)="addHolding()"></app-allocation-donut>

    <app-target-table *ngIf="editable() && allocation() as current" [allocation]="current" [targets]="targets">
    </app-target-table>

    <div class="sr-only" aria-live="polite" role="status" data-testid="target-announcer">{{ targets.announcement() }}</div>
  `,
  styles: [`
    :host { display: block; --subtle: #a1a1ae; }
    .hint {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: 6px 18px;
      margin-bottom: 8px;
      font-size: 12px;
      color: var(--subtle);
    }
    .key { display: inline-flex; align-items: center; gap: 6px; }
    .ring { width: 12px; height: 12px; border-radius: 50%; border: 3px solid var(--subtle); }
    .ring.target { border-width: 2px; outline: 2px solid var(--subtle); outline-offset: 2px; width: 8px; height: 8px; }
    .how { margin-left: auto; }
    .knob {
      display: inline-block;
      width: 10px;
      height: 10px;
      margin: 0 2px -1px;
      border-radius: 50%;
      background: var(--foreground);
    }
    app-target-table { margin-top: 24px; }
    @media (max-width: 640px) { .how { margin-left: 0; } }
  `],
  providers: [TargetAllocation]
})
export class RebalanceToolComponent {
  readonly context = inject(ToolsContext);
  readonly targets = inject(TargetAllocation);
  private readonly router = inject(Router);

  readonly state = computed<'loading' | 'error' | 'ready'>(() => {
    if (this.context.error()) {
      return 'error';
    }
    return this.context.loading() || !this.context.account() ? 'loading' : 'ready';
  });

  /**
   * Every open position at its live price, plus cash. Recomputes as prices tick. Cash keeps a
   * slice even at $0, so the client can still choose to hold some.
   */
  private readonly current = computed<Allocation | null>(() => {
    const account = this.context.account();
    if (!account) {
      return null;
    }
    const holdings = this.context.holdings().map(h => ({ symbol: h.symbol, name: h.name, quantity: h.quantity, price: h.price }));
    return buildAllocation(holdings, account.cash, { alwaysCash: holdings.length > 0 });
  });

  /** The current allocation in the targets' fixed ring order, so the two rings line up. */
  readonly allocation = computed(() => this.targets.order(this.current()));

  /** Targets need something to reshape: at least one holding. */
  readonly editable = computed(() => this.state() === 'ready' && (this.current()?.slices.some(s => s.kind === 'holding') ?? false));

  constructor() {
    effect(() => this.targets.sync(this.current(), this.context.account()?.accountId ?? null));
  }

  /** Until the donut can add a holding itself (TT-8), browse instruments to buy one. */
  addHolding(): void {
    this.router.navigateByUrl('/instruments');
  }
}
