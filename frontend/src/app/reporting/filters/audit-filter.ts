import { Component, EventEmitter, Input, OnChanges, Output, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import {
  AuditFilter, EMPTY_AUDIT_FILTER, ORDER_SIDES, ORDER_STATUSES, OrderSide, OrderStatus, isOrderId, normalizeAudit
} from './audit';

/**
 * Staff Dashboards: trading operations' audit filter, plus an order lookup that jumps straight to
 * one order's lifecycle trace. Filters apply together on Apply, so a half-typed id never narrows
 * the audit; Reset clears them all.
 */
@Component({
  selector: 'app-audit-filter',
  imports: [CommonModule, ReactiveFormsModule],
  template: `
    <div class="audit-filters">
      <form class="filter-bar" [formGroup]="form" (ngSubmit)="submit()" aria-label="Audit filters">
        <label class="filter-field">
          <span class="filter-label">Submitted from</span>
          <input type="datetime-local" class="filter-input" formControlName="from" [class.invalid]="rangeError()" />
        </label>
        <label class="filter-field">
          <span class="filter-label">Submitted to</span>
          <input type="datetime-local" class="filter-input" formControlName="to" [class.invalid]="rangeError()" />
        </label>
        <label class="filter-field">
          <span class="filter-label">Client id</span>
          <input type="text" class="filter-input" formControlName="clientId" placeholder="Any client" autocomplete="off" />
        </label>
        <label class="filter-field">
          <span class="filter-label">Account id</span>
          <input type="text" class="filter-input" formControlName="accountId" placeholder="Any account" autocomplete="off" />
        </label>
        <label class="filter-field narrow">
          <span class="filter-label">Symbol</span>
          <input type="text" class="filter-input" formControlName="symbol" placeholder="Any" autocomplete="off" />
        </label>
        <label class="filter-field narrow">
          <span class="filter-label">Side</span>
          <select class="filter-input" formControlName="side">
            <option value="">Any</option>
            <option *ngFor="let side of sides" [value]="side">{{ side | titlecase }}</option>
          </select>
        </label>
        <label class="filter-field narrow">
          <span class="filter-label">Status</span>
          <select class="filter-input" formControlName="status">
            <option value="">Any</option>
            <option *ngFor="let status of statuses" [value]="status">{{ status | titlecase }}</option>
          </select>
        </label>
        <div class="filter-actions">
          <button type="button" class="btn-reset" (click)="reset()">Reset</button>
          <button type="submit" class="btn-apply">Apply</button>
        </div>
        <span class="filter-error" *ngIf="rangeError()" role="alert">{{ rangeError() }}</span>
      </form>

      <form class="filter-bar lookup" [formGroup]="lookup" (ngSubmit)="traceOrder()" aria-label="Trace an order">
        <label class="filter-field grow">
          <span class="filter-label">Trace an order</span>
          <input type="text" class="filter-input mono" formControlName="orderId" autocomplete="off"
                 placeholder="Order id, e.g. 3f2b8c1e-…" [class.invalid]="lookupError()" />
        </label>
        <div class="filter-actions">
          <button type="submit" class="btn-apply">Trace lifecycle</button>
        </div>
        <span class="filter-error" *ngIf="lookupError()" role="alert">{{ lookupError() }}</span>
      </form>
    </div>
  `,
  styleUrls: ['./filters.css'],
  styles: `
    .audit-filters { display: flex; flex-direction: column; gap: 12px; }
    .filter-field { flex: 1 1 180px; }
    .filter-field.narrow { flex: 0 1 120px; }
    .filter-field.narrow .filter-input { min-width: 100px; }
    .filter-field.grow { flex: 1 1 320px; }
    .mono { font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace; font-size: 13px; }
  `
})
export class AuditFilterComponent implements OnChanges {
  @Input() value: AuditFilter = EMPTY_AUDIT_FILTER;
  @Output() readonly apply = new EventEmitter<AuditFilter>();
  @Output() readonly trace = new EventEmitter<string>();

  readonly sides = ORDER_SIDES;
  readonly statuses = ORDER_STATUSES;
  readonly rangeError = signal('');
  readonly lookupError = signal('');

  readonly form = new FormGroup({
    from: new FormControl('', { nonNullable: true }),
    to: new FormControl('', { nonNullable: true }),
    clientId: new FormControl('', { nonNullable: true }),
    accountId: new FormControl('', { nonNullable: true }),
    symbol: new FormControl('', { nonNullable: true }),
    side: new FormControl('', { nonNullable: true }),
    status: new FormControl('', { nonNullable: true })
  });
  readonly lookup = new FormGroup({
    orderId: new FormControl('', { nonNullable: true })
  });

  ngOnChanges(): void {
    const v = this.value;
    this.form.setValue({
      from: v.from ?? '', to: v.to ?? '', clientId: v.clientId ?? '', accountId: v.accountId ?? '',
      symbol: v.symbol ?? '', side: v.side ?? '', status: v.status ?? ''
    });
    this.rangeError.set('');
  }

  submit(): void {
    const v = this.form.getRawValue();
    const filter = normalizeAudit({
      from: v.from || null, to: v.to || null, clientId: v.clientId, accountId: v.accountId,
      symbol: v.symbol, side: (v.side || null) as OrderSide | null, status: (v.status || null) as OrderStatus | null
    });
    if (filter.from && filter.to && filter.from > filter.to) {
      this.rangeError.set('"Submitted from" must be on or before "Submitted to".');
      return;
    }
    this.rangeError.set('');
    this.apply.emit(filter);
  }

  reset(): void {
    this.form.reset();
    this.rangeError.set('');
    this.apply.emit(EMPTY_AUDIT_FILTER);
  }

  traceOrder(): void {
    const orderId = this.lookup.getRawValue().orderId.trim();
    if (!isOrderId(orderId)) {
      this.lookupError.set('Enter a full order id: 32 hex digits in the form 8-4-4-4-12.');
      return;
    }
    this.lookupError.set('');
    this.trace.emit(orderId.toLowerCase());
  }
}
