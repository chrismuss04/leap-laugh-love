import { Component, computed, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { Router } from '@angular/router';
import { AuditFilterState } from '../filters/filter-state';
import { AuditFilterComponent } from '../filters/audit-filter';
import { AuditFilter, activeAuditFilters } from '../filters/audit';
import { ReportPanelComponent } from '../shared/report-panel';
import { StatTileComponent } from '../shared/stat-tile';
import { TradeReconstructionComponent } from '../trade-reconstruction';

/**
 * Staff Dashboards: trading operations' home - who placed which order, when, and how it ended,
 * with a lookup that retraces any one order through executions and the cash and holdings ledgers.
 * Reports aren't built yet; each panel marks where one goes and reads the filter from
 * AuditFilterState, which this page provides.
 */
@Component({
  selector: 'app-trading-ops-dashboard',
  imports: [CommonModule, AuditFilterComponent, ReportPanelComponent, StatTileComponent, TradeReconstructionComponent],
  providers: [AuditFilterState],
  templateUrl: './trading-ops.html',
  styleUrl: '../shared/dashboard.css'
})
export class TradingOpsDashboardComponent {
  private readonly router = inject(Router);
  readonly filters = inject(AuditFilterState);

  readonly activeFilters = computed(() => activeAuditFilters(this.filters.value()));

  applyFilters(filter: AuditFilter): void {
    this.filters.set(filter);
  }

  // The audit filters ride along, so "Back to audit" returns to the same view.
  traceOrder(orderId: string): void {
    this.router.navigate(['/reporting/orders', orderId], { queryParamsHandling: 'preserve' });
  }
}
