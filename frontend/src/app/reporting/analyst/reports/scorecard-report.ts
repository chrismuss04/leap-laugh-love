import { Component, computed, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ReportsService, ScorecardMetric, ScorecardMonth, ScorecardReport } from '../../../services/reports';
import { formatMoney } from '../../../shared/format';
import { ReportPanelComponent } from '../../shared/report-panel';
import { AnalystReport } from './analyst-report';
import { barWidth, change, compactMoney, count, notableTrend, NOTABLE_CHANGE_PCT } from './report-format';

type MetricKey = 'volume' | 'filledOrders' | 'averageTradeValue' | 'activeClients' | 'registrations';

/** Which earlier month each figure's change is measured against. */
export type Comparison = 'month' | 'year';

/** Analyst reports: the headline figures month by month, each with its change on an earlier month. */
@Component({
  selector: 'app-scorecard-report',
  imports: [CommonModule, ReportPanelComponent],
  templateUrl: './scorecard-report.html',
  styleUrl: './reports.css',
  styles: `
    .compare {
      display: inline-flex;
      gap: 2px;
      margin-bottom: 12px;
      padding: 2px;
      border: 1px solid var(--border);
      border-radius: 999px;
    }
    .compare button {
      padding: 4px 12px;
      border: 0;
      background: transparent;
      cursor: pointer;
      border-radius: 999px;
      color: var(--muted-foreground);
      font-size: 12px;
      font-weight: 500;
    }
    .compare button[aria-pressed='true'] {
      background: var(--muted);
      color: var(--foreground);
    }
    .compare button:focus-visible {
      outline: 2px solid var(--ring);
      outline-offset: 1px;
    }
    .metric {
      display: grid;
      grid-template-columns: minmax(48px, 1fr) auto;
      align-items: center;
      column-gap: 10px;
      min-width: 128px;
    }
    .metric > :first-child { grid-row: 1 / span 2; }
    .metric .figure { text-align: right; }
    .metric .change { grid-column: 2; text-align: right; }
  `
})
export class ScorecardReportComponent extends AnalystReport<ScorecardReport> {
  private readonly reports = inject(ReportsService);

  readonly columns: { key: MetricKey; label: string; format: (value: number | null) => string }[] = [
    { key: 'volume', label: 'Volume', format: compactMoney },
    { key: 'filledOrders', label: 'Filled orders', format: count },
    { key: 'averageTradeValue', label: 'Avg trade value', format: formatMoney },
    { key: 'activeClients', label: 'Active clients', format: count },
    { key: 'registrations', label: 'Sign-ups', format: count }
  ];

  readonly comparison = signal<Comparison>('month');
  readonly notablePct = NOTABLE_CHANGE_PCT;

  /**
   * The busiest month's volume, which the volume bars are scaled to. Volume is the one figure with
   * a bar: on every column, a year of gradual change drew 60 near-identical bars.
   */
  readonly maxVolume = computed(() => Math.max(0, ...(this.report()?.months ?? []).map(m => m.volume.value ?? 0)));

  readonly change = change;
  readonly notableTrend = notableTrend;
  readonly barWidth = barWidth;

  metric(month: ScorecardMonth, key: MetricKey): ScorecardMetric {
    return month[key];
  }

  /** The figure's change against the month the toggle picks. */
  changeOf(figure: ScorecardMetric): number | null {
    return this.comparison() === 'month' ? figure.monthOverMonthPct : figure.yearOverYearPct;
  }

  protected fetch() {
    return this.reports.getScorecard();
  }

  protected isEmpty(report: ScorecardReport): boolean {
    return report.months.every(m => !m.volume.value && !m.activeClients.value && !m.registrations.value);
  }
}
