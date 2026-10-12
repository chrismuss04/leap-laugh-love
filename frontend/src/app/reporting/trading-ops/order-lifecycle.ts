import { Component, computed, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { map } from 'rxjs';
import { isOrderId } from '../filters/audit';
import { ReportPanelComponent } from '../shared/report-panel';
import { LifecycleTimelineComponent } from './lifecycle-timeline';
import { LifecycleStage, OrderOutcome, emptyLifecycle, withOutcome } from './lifecycle';

/**
 * Staff Dashboards: one order retraced end to end - every step from submission to the reporting
 * store with its timestamp, then the execution, cash ledger and position records behind them.
 * Nothing loads yet: `recorded` and `outcome` are where the lifecycle report will put its data.
 */
@Component({
  selector: 'app-order-lifecycle',
  imports: [CommonModule, RouterLink, ReportPanelComponent, LifecycleTimelineComponent],
  templateUrl: './order-lifecycle.html',
  styleUrls: ['../shared/dashboard.css', './order-lifecycle.css']
})
export class OrderLifecycleComponent {
  readonly orderId = toSignal(inject(ActivatedRoute).paramMap.pipe(map(params => params.get('orderId') ?? '')),
    { requireSync: true });
  readonly validId = computed(() => isOrderId(this.orderId()));

  /** The stages with their recorded timestamps; every stage awaits data until the report is built. */
  readonly recorded = signal<LifecycleStage[]>(emptyLifecycle());
  /** The order's final status, once known; it decides which stages apply. */
  readonly outcome = signal<OrderOutcome | null>(null);

  readonly stages = computed(() => withOutcome(this.recorded(), this.outcome()));
}
