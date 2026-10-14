import { Injectable, computed, signal } from '@angular/core';
import { ReportWindow } from '../../../services/reports';
import { monthLabel } from './report-format';

/**
 * Analyst reports: the months the 12-month reports cover, as reporting-app last said, so the page
 * can name them once above all six panels instead of in each. Provided by the analyst page; the
 * reports share one window, so whichever loads first sets it.
 */
@Injectable()
export class ReportWindowState {
  readonly window = signal<ReportWindow | null>(null);

  /** 'Oct 2025 – Sep 2026', or null until a report has loaded. */
  readonly label = computed(() => {
    const window = this.window();
    return window ? `${monthLabel(window.firstMonth)} – ${monthLabel(window.lastMonth)}` : null;
  });
}
