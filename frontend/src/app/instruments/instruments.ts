import { Component, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { BehaviorSubject, catchError, EMPTY, switchMap, tap } from 'rxjs';
import { InstrumentOverview, InstrumentPeriod, InstrumentsService } from '../services/instruments';
import { direction, formatNumber, formatSignedPercent } from '../shared/format';

@Component({
  selector: 'app-instruments',
  imports: [CommonModule],
  templateUrl: './instruments.html',
  styleUrl: './instruments.css'
})
export class InstrumentsComponent {
  readonly period = signal<InstrumentPeriod>('today');
  readonly overview = signal<InstrumentOverview | null>(null);
  readonly loading = signal(true);
  readonly error = signal<string | null>(null);
  readonly direction = direction;
  readonly number = formatNumber;
  readonly percent = formatSignedPercent;
  private readonly service = inject(InstrumentsService);
  private readonly requests = new BehaviorSubject<InstrumentPeriod>('today');

  constructor() {
    this.requests.pipe(
      tap(() => {
        this.loading.set(true);
        this.error.set(null);
        this.overview.set(null);
      }),
      // Cancel stale requests when the user changes periods or retries.
      switchMap(period => this.service.getOverview(period).pipe(
        catchError(() => {
          this.error.set('Could not load instruments. Please try again.');
          this.loading.set(false);
          return EMPTY;
        })
      )),
      takeUntilDestroyed()
    ).subscribe(response => {
      this.overview.set(response);
      this.loading.set(false);
    });
  }

  selectPeriod(value: string): void {
    if (value !== 'today' && value !== 'week' && value !== 'month') return;
    this.period.set(value);
    this.requests.next(value);
  }

  retry(): void { this.requests.next(this.period()); }

  signed(value: number | null): string {
    if (value === null) return formatNumber(null);
    return `${value > 0 ? '+' : value < 0 ? '\u2212' : ''}${formatNumber(Math.abs(value))}`;
  }

  assetLabel(value: string | null): string {
    return ({ EQUITY: 'Equity', FX: 'FX', CRYPTO: 'Crypto' } as Record<string, string>)[value ?? '']
      ?? value ?? 'Unclassified';
  }
}
