import { Component, computed, inject } from '@angular/core';
import { Router } from '@angular/router';
import { ToolsContext } from '../tools-context';
import { Allocation, buildAllocation } from './allocation';
import { AllocationDonutComponent } from './allocation-donut';

/**
 * Rebalance Donut's workspace: the selected account's current allocation, priced live. Reshaping
 * it into trades comes in later stories.
 */
@Component({
  selector: 'app-rebalance-tool',
  imports: [AllocationDonutComponent],
  template: `
    <app-allocation-donut [allocation]="allocation()" [state]="state()" [errorMessage]="context.error()"
                          (retry)="context.load()" (addHolding)="addHolding()"></app-allocation-donut>
  `,
  styles: [`:host { display: block; }`]
})
export class RebalanceToolComponent {
  readonly context = inject(ToolsContext);
  private readonly router = inject(Router);

  readonly state = computed<'loading' | 'error' | 'ready'>(() => {
    if (this.context.error()) {
      return 'error';
    }
    return this.context.loading() || !this.context.account() ? 'loading' : 'ready';
  });

  /** Every open position at its live price, plus cash. Recomputes as prices tick. */
  readonly allocation = computed<Allocation | null>(() => {
    const account = this.context.account();
    if (!account) {
      return null;
    }
    const holdings = this.context.holdings().map(h => ({ symbol: h.symbol, name: h.name, quantity: h.quantity, price: h.price }));
    return buildAllocation(holdings, account.cash);
  });

  /** Until the donut can add a holding itself (TT-8), browse instruments to buy one. */
  addHolding(): void {
    this.router.navigateByUrl('/instruments');
  }
}
