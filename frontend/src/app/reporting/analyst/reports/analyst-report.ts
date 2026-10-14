import { Directive, OnInit, inject, signal } from '@angular/core';
import { Observable } from 'rxjs';
import { ReportWindow } from '../../../services/reports';
import { ReportState } from '../../shared/report-panel';
import { ReportWindowState } from './report-window-state';
import { monthLabel } from './report-format';

/**
 * Analyst reports: what every 12-month report component shares. Loads its report once, keeps the
 * panel's state in step (loading, empty, error, ready) and reloads on the panel's "Try again".
 * These reports always cover the last 12 complete UTC months, whatever the page's period filter says;
 * each passes the months on to ReportWindowState, so the page names them once for all six.
 */
@Directive()
export abstract class AnalystReport<T extends { window: ReportWindow }> implements OnInit {
  readonly report = signal<T | null>(null);
  readonly state = signal<ReportState>('loading');

  // Optional so a report still works on its own, outside the analyst page.
  private readonly windowState = inject(ReportWindowState, { optional: true });

  readonly monthLabel = monthLabel;

  protected abstract fetch(): Observable<T>;

  /** True when the report has nothing to show, so the panel says so instead of drawing empty rows. */
  protected abstract isEmpty(report: T): boolean;

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    this.state.set('loading');
    this.fetch().subscribe({
      next: report => {
        this.report.set(report);
        this.windowState?.window.set(report.window);
        this.state.set(this.isEmpty(report) ? 'empty' : 'ready');
      },
      error: () => {
        this.report.set(null);
        this.state.set('error');
      }
    });
  }
}
