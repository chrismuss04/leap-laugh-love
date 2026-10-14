import { Component, computed, effect, inject, signal, untracked } from '@angular/core';
import { CommonModule, formatDate } from '@angular/common';
import { Router, RouterLink } from '@angular/router';
import { OrderCounts, RejectionCount, TradeOpsService, TradeSummary } from '../../services/trade-ops';
import { AuditFilterState } from '../filters/filter-state';
import { AuditFilterComponent } from '../filters/audit-filter';
import { AuditFilter, activeAuditFilters, auditPeriod, missingAuditCriteria, toTradeSearch } from '../filters/audit';
import { ReportPanelComponent, ReportState } from '../shared/report-panel';
import { StatTileComponent } from '../shared/stat-tile';
import { formatDuration } from './lifecycle';

const NO_DATA = 'No data available';

/**
 * Staff Dashboards: trading operations' home - who placed which order, when, and how it ended,
 * with a lookup that retraces any one order through executions and the cash and holdings ledgers.
 * The order audit log and the headline counts search with the filter in AuditFilterState, which
 * this page provides, once it has a period and a client. Rejections by reason cover the filter's
 * period across every client; stuck orders are always current, across every client.
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

  /** 'idle' until the filter can be searched: the tiles show a dash and nothing is counted. */
  readonly counts = signal<OrderCounts | null>(null);
  readonly countsState = signal<'idle' | 'loading' | 'ready' | 'error'>('idle');
  readonly tiles = computed(() => {
    const counts = this.counts();
    const figure = (value: number | undefined) => value === undefined ? null : value.toLocaleString('en-US');
    return [
      { label: 'Orders submitted', value: figure(counts?.submitted) },
      { label: 'Filled', value: figure(counts?.filled) },
      { label: 'Rejected', value: figure(counts?.rejected) },
      { label: 'Awaiting execution', value: figure(counts?.awaitingExecution), hint: 'Submitted, accepted or open' }
    ];
  });

  readonly rejections = signal<RejectionCount[]>([]);
  readonly rejectionsState = signal<ReportState>('empty');
  readonly rejectionsMessage = signal('');
  /** Rejections ignore the client and other filters, so the panel says it covers every client. */
  readonly rejectionsScope = computed(() => {
    const { from, to } = this.filters.value();
    const when = (value: string) => formatDate(value, 'd MMM yyyy HH:mm', 'en-US');
    return from && to ? `All clients · ${when(from)} – ${when(to)}` : 'All clients';
  });

  readonly stuckOrders = signal<TradeSummary[]>([]);
  readonly stuckState = signal<ReportState>('loading');
  readonly stuckMessage = signal('');
  /** When the stuck orders were loaded, which their waiting times are measured to. */
  private readonly stuckCheckedAt = signal(Date.now());

  // Only a change to the period reloads rejections; the other filters don't narrow them.
  private readonly period = computed(() => auditPeriod(this.filters.value()),
    { equal: (a, b) => a?.from === b?.from && a?.to === b?.to });

  constructor() {
    effect(() => {
      const filter = this.filters.value();
      untracked(() => {
        this.searchOrders(filter);
        this.countOrders(filter);
      });
    });
    effect(() => {
      const period = this.period();
      untracked(() => this.loadRejections(period));
    });
    this.loadStuckOrders();
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

  /** How long a stuck order has waited since it was submitted, e.g. "12 min". */
  waitingFor(order: TradeSummary): string {
    return formatDuration(Math.max(0, this.stuckCheckedAt() - Date.parse(order.submittedAt)));
  }

  retrySearch(): void {
    this.searchOrders(this.filters.value());
  }

  retryCounts(): void {
    this.countOrders(this.filters.value());
  }

  retryRejections(): void {
    this.loadRejections(this.period());
  }

  retryStuckOrders(): void {
    this.loadStuckOrders();
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

  // Counts the same orders the audit log searches, so the tiles always match the table.
  private countOrders(filter: AuditFilter): void {
    this.counts.set(null);
    if (missingAuditCriteria(filter)) {
      this.countsState.set('idle');
      return;
    }
    this.countsState.set('loading');
    this.tradeOps.counts(toTradeSearch(filter)).subscribe({
      next: counts => {
        this.counts.set(counts);
        this.countsState.set('ready');
      },
      error: () => this.countsState.set('error')
    });
  }

  private loadRejections(period: { from: string; to: string } | null): void {
    if (!period) {
      this.rejections.set([]);
      this.rejectionsState.set('empty');
      this.rejectionsMessage.set('Enter a time period in the filters above to see rejections.');
      return;
    }
    this.rejectionsState.set('loading');
    this.tradeOps.rejections(period.from, period.to).subscribe({
      next: rejections => {
        this.rejections.set(rejections);
        this.rejectionsState.set(rejections.length ? 'ready' : 'empty');
        this.rejectionsMessage.set(NO_DATA);
      },
      error: err => {
        this.rejectionsState.set('error');
        this.rejectionsMessage.set(err.error?.message || "Couldn't load rejections by reason.");
      }
    });
  }

  private loadStuckOrders(): void {
    this.stuckState.set('loading');
    this.tradeOps.stuckOrders().subscribe({
      next: orders => {
        this.stuckCheckedAt.set(Date.now());
        this.stuckOrders.set(orders);
        this.stuckState.set(orders.length ? 'ready' : 'empty');
        this.stuckMessage.set(NO_DATA);
      },
      error: err => {
        this.stuckState.set('error');
        this.stuckMessage.set(err.error?.message || "Couldn't load stuck orders.");
      }
    });
  }
}
