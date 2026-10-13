import { Component, computed, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { takeUntilDestroyed, toObservable } from '@angular/core/rxjs-interop';
import { Subject, catchError, combineLatest, distinctUntilChanged, map, of, startWith, switchMap } from 'rxjs';
import { PeriodFilterState } from '../filters/filter-state';
import { PeriodFilterComponent } from '../filters/period-filter';
import { PERIODS, PeriodFilter } from '../filters/period';
import { ReportPanelComponent } from '../shared/report-panel';
import { StatTileComponent } from '../shared/stat-tile';
import { ActivityReportComponent } from '../activity-report';
import { BusinessInsights, ReportsService } from '../../services/reports';
import { direction, formatMoney, formatSignedPercent, percentChange } from '../../shared/format';

/** One headline tile: its figure, what it measures, and how it moved since the previous period. */
interface InsightTile {
  label: string;
  value: string | null;
  hint: string;
  trend: string;
  direction: 'gain' | 'loss' | 'flat';
}

const count = new Intl.NumberFormat('en-US');

/**
 * Staff Dashboards: the commercial analyst's home - trading volume, popular instruments, client
 * growth and order size over a chosen period. The headline tiles and the activity report are
 * live; the tiles follow the period filter, the activity report has its own filters. The other
 * reports aren't built yet - each panel marks where one goes and reads the period from
 * PeriodFilterState, which this page provides.
 */
@Component({
  selector: 'app-analyst-dashboard',
  imports: [CommonModule, PeriodFilterComponent, ReportPanelComponent, StatTileComponent, ActivityReportComponent],
  providers: [PeriodFilterState],
  templateUrl: './analyst.html',
  styleUrl: '../shared/dashboard.css'
})
export class AnalystDashboardComponent {
  readonly filters = inject(PeriodFilterState);
  private readonly reports = inject(ReportsService);
  private readonly retry = new Subject<void>();

  readonly insights = signal<BusinessInsights | null>(null);
  readonly insightsState = signal<'loading' | 'ready' | 'error'>('loading');

  readonly periodLabel = computed(() => {
    const filter = this.filters.value();
    return filter.period === 'CUSTOM' ? 'Custom period' : PERIODS.find(p => p.period === filter.period)!.label;
  });

  /** The five headline tiles; their figures stay a dash until the insights for the period load. */
  readonly tiles = computed<InsightTile[]>(() => {
    const insights = this.insights();
    const now = insights?.current;
    const before = insights?.previous;
    const top = insights?.topInstruments ?? [];
    return [
      tile('Trading volume', 'Value of filled orders', now && formatMoney(now.tradedValue), now?.tradedValue, before?.tradedValue),
      tile('Average order value', 'Per filled order', now && formatMoney(now.averageOrderValue),
        now?.averageOrderValue, before?.averageOrderValue),
      tile('New registrations', 'Clients who signed up', now && count.format(now.newRegistrations),
        now?.newRegistrations, before?.newRegistrations),
      tile('Active clients', 'Placed at least one order', now && count.format(now.activeClients),
        now?.activeClients, before?.activeClients),
      {
        label: 'Most active instrument',
        value: insights ? top[0]?.symbol ?? '—' : null,
        hint: top.length ? `${count.format(top[0].tradeCount)} ${top[0].tradeCount === 1 ? 'trade' : 'trades'}` : 'By number of trades',
        trend: top.length > 1 ? `Then ${top.slice(1).map(i => i.symbol).join(', ')}` : '',
        direction: 'flat'
      }
    ];
  });

  constructor() {
    // The URL emits on every navigation, so only a change of days (or a retry) reloads.
    const range = toObservable(this.filters.range).pipe(
      distinctUntilChanged((a, b) => a.from === b.from && a.to === b.to));
    combineLatest([range, this.retry.pipe(startWith(undefined))]).pipe(
      switchMap(([{ from, to }]) => {
        this.insights.set(null);
        this.insightsState.set('loading');
        return this.reports.getInsights(from, to).pipe(map(insights => ({ insights })), catchError(() => of(null)));
      }),
      takeUntilDestroyed()
    ).subscribe(result => {
      this.insights.set(result?.insights ?? null);
      this.insightsState.set(result ? 'ready' : 'error');
    });
  }

  setPeriod(filter: PeriodFilter): void {
    this.filters.set(filter);
  }

  retryInsights(): void {
    this.retry.next();
  }
}

/** A tile for a figure that can be compared with the previous period. */
function tile(label: string, hint: string, value: string | undefined, now: number | null | undefined,
              before: number | null | undefined): InsightTile {
  if (value === undefined) {
    return { label, value: null, hint, trend: '', direction: 'flat' };
  }
  const change = percentChange(now, before);
  return {
    label, value, hint,
    // No change to show when the previous period had nothing to compare with.
    trend: change === null ? 'No earlier figure to compare' : `${formatSignedPercent(change)} vs previous period`,
    direction: direction(change)
  };
}
