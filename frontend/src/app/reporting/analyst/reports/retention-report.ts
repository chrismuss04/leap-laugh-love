import { Component, ElementRef, computed, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ReportsService, RetentionCell, RetentionCohort, RetentionReport } from '../../../services/reports';
import { ReportPanelComponent } from '../../shared/report-panel';
import { AnalystReport } from './analyst-report';
import { count, monthLabel, percent } from './report-format';

/** Above this share a heat cell is dark enough that its figure switches to dark text. */
const STRONG_HEAT_PCT = 55;

/**
 * Analyst reports: what share of each month's sign-ups placed an order in each month after, as a
 * heat map. Hovering or focusing a cell lights its cohort's row and its month's column. One cell
 * is in the tab order at a time; the arrow keys move between cells.
 */
@Component({
  selector: 'app-retention-report',
  imports: [CommonModule, ReportPanelComponent],
  templateUrl: './retention-report.html',
  styleUrls: ['./reports.css', './retention-report.css']
})
export class RetentionReportComponent extends AnalystReport<RetentionReport> {
  private readonly reports = inject(ReportsService);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);

  /** Months since sign-up, 0 to 11: the oldest cohort has a cell for every one. */
  readonly offsets = computed(() =>
    Array.from({ length: Math.max(0, ...(this.report()?.cohorts ?? []).map(c => c.months.length)) }, (_, i) => i));

  /** The cell in the tab order, as [cohort row, months since sign-up]. */
  readonly focusCell = signal<[number, number]>([0, 0]);
  /** The cell the pointer or focus is on, whose row and column are lit. */
  readonly litCell = signal<[number, number] | null>(null);

  readonly count = count;
  readonly percent = percent;
  readonly strongHeat = STRONG_HEAT_PCT;

  cellLabel(cohort: RetentionCohort, cell: RetentionCell): string {
    return `${monthLabel(cohort.cohort)} sign-ups, month ${cell.monthsSinceSignUp}: ${percent(cell.activePct)}, `
      + `${count(cell.activeClients)} of ${count(cohort.clients)} clients`;
  }

  isFocusCell(row: number, offset: number): boolean {
    const [r, c] = this.focusCell();
    return r === row && c === offset;
  }

  inLitRow(row: number): boolean {
    return this.litCell()?.[0] === row;
  }

  inLitColumn(offset: number): boolean {
    return this.litCell()?.[1] === offset;
  }

  light(row: number, offset: number): void {
    this.litCell.set([row, offset]);
  }

  focused(row: number, offset: number): void {
    this.focusCell.set([row, offset]);
    this.litCell.set([row, offset]);
  }

  /** Arrow keys, Home and End move between the cells that exist; the triangle's blank corner is skipped. */
  move(event: KeyboardEvent): void {
    const cohorts = this.report()?.cohorts ?? [];
    const [row, offset] = this.focusCell();
    const width = (r: number) => cohorts[r]?.months.length ?? 0;
    let next: [number, number] | null = null;
    switch (event.key) {
      case 'ArrowRight': next = offset + 1 < width(row) ? [row, offset + 1] : null; break;
      case 'ArrowLeft': next = offset > 0 ? [row, offset - 1] : null; break;
      case 'ArrowDown': next = row + 1 < cohorts.length && offset < width(row + 1) ? [row + 1, offset] : null; break;
      case 'ArrowUp': next = row > 0 ? [row - 1, offset] : null; break;
      case 'Home': next = [row, 0]; break;
      case 'End': next = [row, width(row) - 1]; break;
      default: return;
    }
    event.preventDefault();
    if (next) {
      this.focused(...next);
      const [r, c] = next;
      this.host.nativeElement.querySelector<HTMLElement>(`[data-cell="${r}-${c}"]`)?.focus();
    }
  }

  protected fetch() {
    return this.reports.getRetention();
  }

  protected isEmpty(report: RetentionReport): boolean {
    return report.cohorts.every(c => c.clients === 0);
  }
}
