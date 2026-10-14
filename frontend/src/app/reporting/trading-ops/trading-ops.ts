import { Component, computed, effect, inject, signal, untracked } from '@angular/core';
import { CommonModule } from '@angular/common';
import { Router, RouterLink } from '@angular/router';
import { TradeOpsService, TradeSummary } from '../../services/trade-ops';
import { AuditFilterState } from '../filters/filter-state';
import { AuditFilterComponent } from '../filters/audit-filter';
import { AuditFilter, activeAuditFilters, missingAuditCriteria, toTradeSearch } from '../filters/audit';
import { ReportPanelComponent, ReportState } from '../shared/report-panel';
import { StatTileComponent } from '../shared/stat-tile';

/**
 * Staff Dashboards: trading operations' home - who placed which order, when, and how it ended,
 * with a lookup that retraces any one order through executions and the cash and holdings ledgers.
 * The order audit log searches with the filter in AuditFilterState, which this page provides, once
 * it has a period and a client; the other reports aren't built yet and mark where one goes.
 */
@Component({
  selector: 'app-trading-ops-dashboard',
  imports: [CommonModule, RouterLink, AuditFilterComponent, ReportPanelComponent, StatTileComponent],
  providers: [AuditFilterState],
  templateUrl: './trading-ops.html',
  styleUrls: ['../shared/dashboard.css', './trading-ops.css']
})
export class TradingOpsDashboardComponent {
  private readonly router = inject(Router);
  private readonly tradeOps = inject(TradeOpsService);
  readonly filters = inject(AuditFilterState);

  readonly activeFilters = computed(() => activeAuditFilters(this.filters.value()));

  readonly orders = signal<TradeSummary[]>([]);
  readonly auditLogState = signal<ReportState>('empty');
  readonly auditLogMessage = signal('');

  constructor() {
    effect(() => {
      const filter = this.filters.value();
      untracked(() => this.searchOrders(filter));
    });
  }

  applyFilters(filter: AuditFilter): void {
    this.filters.set(filter);
  }

  // The audit filters ride along, so "Back to audit" returns to the same view.
  traceOrder(orderId: string): void {
    this.router.navigate(['/reporting/orders', orderId], { queryParamsHandling: 'preserve' });
  }

  trackOrder(_index: number, order: TradeSummary): string {
    return order.orderId;
  }

  retrySearch(): void {
    this.searchOrders(this.filters.value());
  }

  private searchOrders(filter: AuditFilter): void {
    if (missingAuditCriteria(filter)) {
      // Nothing is looked up, and so nothing logged, until the filter can be searched.
      this.orders.set([]);
      this.auditLogState.set('empty');
      this.auditLogMessage.set('Enter a time period and a client id or email in the filters above to find orders.');
      return;
    }
    this.auditLogState.set('loading');
    this.tradeOps.search(toTradeSearch(filter)).subscribe({
      next: orders => {
        this.orders.set(orders);
        this.auditLogState.set(orders.length ? 'ready' : 'empty');
        this.auditLogMessage.set('No orders match these filters.');
      },
      error: err => {
        this.auditLogState.set('error');
        this.auditLogMessage.set(err.error?.message || "Couldn't load the order audit log.");
      }
    });
  }
}
