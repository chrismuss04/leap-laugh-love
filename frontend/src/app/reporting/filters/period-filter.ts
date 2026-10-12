import { Component, EventEmitter, Input, OnChanges, Output, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { DEFAULT_PERIOD_FILTER, PERIODS, Period, PeriodFilter, validDay } from './period';

/**
 * Staff Dashboards: the commercial analyst's period picker. Presets apply as soon as they're
 * picked; a custom period applies once both days are filled in and run forwards.
 */
@Component({
  selector: 'app-period-filter',
  imports: [CommonModule, ReactiveFormsModule],
  template: `
    <div class="filter-bar" role="group" aria-label="Reporting period">
      <div class="ranges" role="tablist" aria-label="Period">
        <button type="button" role="tab" *ngFor="let p of periods" class="range"
                [class.on]="p.period === selected()" [attr.aria-selected]="p.period === selected()"
                (click)="pick(p.period)">{{ p.label }}</button>
      </div>

      <form *ngIf="selected() === 'CUSTOM'" class="custom" [formGroup]="custom" aria-label="Custom period" (ngSubmit)="applyCustom()">
        <label class="filter-field">
          <span class="filter-label">From</span>
          <input type="date" class="filter-input" formControlName="from" [class.invalid]="error()" />
        </label>
        <label class="filter-field">
          <span class="filter-label">To</span>
          <input type="date" class="filter-input" formControlName="to" [class.invalid]="error()" />
        </label>
        <div class="filter-actions">
          <button type="submit" class="btn-apply">Apply</button>
        </div>
        <span class="filter-error" *ngIf="error()" role="alert">{{ error() }}</span>
      </form>
    </div>
  `,
  styleUrls: ['./filters.css'],
  styles: `
    .custom { display: flex; flex-wrap: wrap; align-items: flex-end; gap: 12px; flex: 1; }
  `
})
export class PeriodFilterComponent implements OnChanges {
  @Input() value: PeriodFilter = DEFAULT_PERIOD_FILTER;
  @Output() readonly periodChange = new EventEmitter<PeriodFilter>();

  readonly periods = PERIODS;
  /** The highlighted tab: Custom stays picked while its days are being filled in. */
  readonly selected = signal<Period>(DEFAULT_PERIOD_FILTER.period);
  readonly error = signal('');
  readonly custom = new FormGroup({
    from: new FormControl('', { nonNullable: true }),
    to: new FormControl('', { nonNullable: true })
  });

  ngOnChanges(): void {
    this.selected.set(this.value.period);
    this.custom.setValue({ from: this.value.from ?? '', to: this.value.to ?? '' });
    this.error.set('');
  }

  pick(period: Period): void {
    this.selected.set(period);
    this.error.set('');
    if (period !== 'CUSTOM') {
      this.periodChange.emit({ period, from: null, to: null });
    }
  }

  applyCustom(): void {
    const from = validDay(this.custom.value.from ?? null);
    const to = validDay(this.custom.value.to ?? null);
    if (!from || !to) {
      this.error.set('Pick both a first and a last day.');
    } else if (from > to) {
      this.error.set('The first day must be on or before the last day.');
    } else {
      this.error.set('');
      this.periodChange.emit({ period: 'CUSTOM', from, to });
    }
  }
}
