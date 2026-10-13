import { Component, computed, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ClientActivityMonth, ClientActivityReport, ReportsService } from '../../../services/reports';
import { ReportPanelComponent } from '../../shared/report-panel';
import { AnalystReport } from './analyst-report';
import { barWidth, count } from './report-format';

/** Analyst reports: each month's active clients by what they did before, and the clients who stopped. */
@Component({
  selector: 'app-client-activity-report',
  imports: [CommonModule, ReportPanelComponent],
  templateUrl: './client-activity-report.html',
  styleUrl: './reports.css',
  styles: `
    .lapsed-high::before {
      content: '';
      display: inline-block;
      width: 6px;
      height: 6px;
      margin-right: 6px;
      border-radius: 50%;
      background: var(--loss);
      vertical-align: middle;
    }
  `
})
export class ClientActivityReportComponent extends AnalystReport<ClientActivityReport> {
  private readonly reports = inject(ReportsService);

  /** The busiest month, which every month's bar is scaled to. */
  readonly maxActive = computed(() => Math.max(0, ...(this.report()?.months ?? []).map(m => m.activeClients)));

  /** The 12 months' mean lapsed clients; months above it are marked, so churn spikes stand out. */
  readonly meanLapsed = computed(() => {
    const months = this.report()?.months ?? [];
    return months.length ? months.reduce((sum, m) => sum + m.lapsedClients, 0) / months.length : 0;
  });

  readonly count = count;
  readonly barWidth = barWidth;

  /** Each kind of active client as a share of the month's active clients, for the stacked bar. */
  segments(month: ClientActivityMonth): { kind: string; label: string; clients: number; share: number }[] {
    const of = (clients: number) => barWidth(clients, month.activeClients);
    return [
      { kind: 'new', label: 'New', clients: month.newClients, share: of(month.newClients) },
      { kind: 'returning', label: 'Returning', clients: month.returningClients, share: of(month.returningClients) },
      { kind: 'reactivated', label: 'Reactivated', clients: month.reactivatedClients, share: of(month.reactivatedClients) }
    ];
  }

  protected fetch() {
    return this.reports.getClientActivity();
  }

  protected isEmpty(report: ClientActivityReport): boolean {
    return report.months.every(m => m.activeClients === 0 && m.lapsedClients === 0);
  }
}
