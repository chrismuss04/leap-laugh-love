import { Component, inject, OnInit, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { AuthService } from '../services/auth.service';
import { ActivityReport, ReportGranularity, ReportInstrument, ReportsService } from '../services/reports';
import { formatMoney } from '../shared/format';

/** yyyy-MM-dd for the UTC day `daysAgo` days before today; report periods are UTC days. */
function utcDay(daysAgo: number): string {
  const day = new Date();
  day.setUTCDate(day.getUTCDate() - daysAgo);
  return day.toISOString().slice(0, 10);
}

@Component({
    selector: 'app-activity-report',
    imports: [CommonModule],
    templateUrl: './activity-report.html',
    styleUrl: './activity-report.css'
})
export class ActivityReportComponent implements OnInit {
  instruments = signal<ReportInstrument[]>([]);
  instrumentId = signal<string | null>(null);
  from = signal(utcDay(6));
  to = signal(utcDay(0));
  granularity = signal<ReportGranularity>('DAILY');

  report = signal<ActivityReport | null>(null);
  loading = signal(false);
  error = signal<string | null>(null);

  readonly formatMoney = formatMoney;

  private authService = inject(AuthService);
  private reportsService = inject(ReportsService);

  ngOnInit() {
    if (!this.authService.isAuthenticated()) {
      return;
    }
    // The report doesn't need the list, so a failure here only leaves the filter at "All".
    this.reportsService.getInstruments().subscribe({
      next: instruments => this.instruments.set(instruments),
      error: () => this.instruments.set([])
    });
    this.generate();
  }

  setInstrument(event: Event) {
    const value = (event.target as HTMLSelectElement).value;
    this.instrumentId.set(value === '' ? null : value);
  }

  setFrom(event: Event) {
    this.from.set((event.target as HTMLInputElement).value);
  }

  setTo(event: Event) {
    this.to.set((event.target as HTMLInputElement).value);
  }

  setGranularity(event: Event) {
    this.granularity.set((event.target as HTMLSelectElement).value as ReportGranularity);
  }

  generate() {
    // Checked here too so an obvious mistake doesn't need a round trip; order-app checks again.
    if (!this.from() || !this.to()) {
      this.error.set('Choose a start and end date');
      return;
    }
    if (this.from() > this.to()) {
      this.error.set('The start date must be on or before the end date');
      return;
    }
    this.loading.set(true);
    this.error.set(null);

    this.reportsService.getActivityReport({
      from: this.from(),
      to: this.to(),
      granularity: this.granularity(),
      instrumentId: this.instrumentId()
    }).subscribe({
      next: report => {
        this.report.set(report);
        this.loading.set(false);
      },
      error: err => {
        this.report.set(null);
        this.error.set(err.error?.message || 'Failed to load the report');
        this.loading.set(false);
      }
    });
  }

  logout() {
    this.authService.logout();
  }
}
