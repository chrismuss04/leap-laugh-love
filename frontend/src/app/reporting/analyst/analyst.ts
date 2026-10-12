import { Component, computed, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { PeriodFilterState } from '../filters/filter-state';
import { PeriodFilterComponent } from '../filters/period-filter';
import { PERIODS, PeriodFilter } from '../filters/period';
import { ReportPanelComponent } from '../shared/report-panel';
import { StatTileComponent } from '../shared/stat-tile';

/**
 * Staff Dashboards: the commercial analyst's home - trading volume, popular instruments, client
 * growth and order size over a chosen period. Reports aren't built yet; each panel marks where one
 * goes and reads the period from PeriodFilterState, which this page provides.
 */
@Component({
  selector: 'app-analyst-dashboard',
  imports: [CommonModule, PeriodFilterComponent, ReportPanelComponent, StatTileComponent],
  providers: [PeriodFilterState],
  templateUrl: './analyst.html',
  styleUrl: '../shared/dashboard.css'
})
export class AnalystDashboardComponent {
  readonly filters = inject(PeriodFilterState);

  readonly periodLabel = computed(() => {
    const filter = this.filters.value();
    return filter.period === 'CUSTOM' ? 'Custom period' : PERIODS.find(p => p.period === filter.period)!.label;
  });

  setPeriod(filter: PeriodFilter): void {
    this.filters.set(filter);
  }
}
