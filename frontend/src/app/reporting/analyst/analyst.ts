import { Component, computed, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { PeriodFilterState } from '../filters/filter-state';
import { PeriodFilterComponent } from '../filters/period-filter';
import { PERIODS, PeriodFilter } from '../filters/period';
import { StatTileComponent } from '../shared/stat-tile';
import { ActivityReportComponent } from '../activity-report';
import { ScorecardReportComponent } from './reports/scorecard-report';
import { ClientActivityReportComponent } from './reports/client-activity-report';
import { ActivationReportComponent } from './reports/activation-report';
import { RetentionReportComponent } from './reports/retention-report';
import { ConcentrationReportComponent } from './reports/concentration-report';
import { OrderSizeReportComponent } from './reports/order-size-report';
import { ReportWindowState } from './reports/report-window-state';

const ANALYST_REPORTS = [
  ScorecardReportComponent, RetentionReportComponent, ClientActivityReportComponent,
  ActivationReportComponent, ConcentrationReportComponent, OrderSizeReportComponent
];

/**
 * Staff Dashboards: the commercial analyst's home. The headline tiles read the period from
 * PeriodFilterState, which this page provides (they aren't built yet); the activity report has its
 * own filters; the six long-range reports below always cover the last 12 complete months.
 */
@Component({
  selector: 'app-analyst-dashboard',
  imports: [CommonModule, PeriodFilterComponent, StatTileComponent, ActivityReportComponent, ...ANALYST_REPORTS],
  providers: [PeriodFilterState, ReportWindowState],
  templateUrl: './analyst.html',
  styleUrls: ['../shared/dashboard.css', './analyst.css']
})
export class AnalystDashboardComponent {
  readonly filters = inject(PeriodFilterState);
  /** The months the 12-month reports cover, named once in their section's heading. */
  readonly reportWindow = inject(ReportWindowState);

  readonly periodLabel = computed(() => {
    const filter = this.filters.value();
    return filter.period === 'CUSTOM' ? 'Custom period' : PERIODS.find(p => p.period === filter.period)!.label;
  });

  setPeriod(filter: PeriodFilter): void {
    this.filters.set(filter);
  }
}
