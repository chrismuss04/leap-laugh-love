import { Component, computed, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ActivationReport, ReportsService } from '../../../services/reports';
import { ReportPanelComponent } from '../../shared/report-panel';
import { AnalystReport } from './analyst-report';
import { count, percent } from './report-format';

const oneDecimal = new Intl.NumberFormat('en-US', { minimumFractionDigits: 1, maximumFractionDigits: 1 });

/** Analyst reports: how many of each month's sign-ups went on to place an order, and how soon. */
@Component({
  selector: 'app-activation-report',
  imports: [CommonModule, ReportPanelComponent],
  templateUrl: './activation-report.html',
  styleUrl: './reports.css'
})
export class ActivationReportComponent extends AnalystReport<ActivationReport> {
  private readonly reports = inject(ReportsService);

  /** Whether any cohort is still inside its first 30 days, which the footnote explains. */
  readonly anyIncomplete = computed(() => (this.report()?.cohorts ?? []).some(c => !c.complete && c.registered > 0));

  readonly count = count;
  readonly percent = percent;

  /** 2.5 days */
  days(value: number | null): string {
    return value == null ? '—' : `${oneDecimal.format(value)} ${value === 1 ? 'day' : 'days'}`;
  }

  protected fetch() {
    return this.reports.getActivation();
  }

  protected isEmpty(report: ActivationReport): boolean {
    return report.cohorts.every(c => c.registered === 0);
  }
}
