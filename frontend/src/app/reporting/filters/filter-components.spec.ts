import { ComponentFixture, TestBed } from '@angular/core/testing';
import { PeriodFilterComponent } from './period-filter';
import { AuditFilterComponent } from './audit-filter';
import { EMPTY_AUDIT_FILTER } from './audit';
import { PeriodFilter } from './period';

function type(fixture: ComponentFixture<unknown>, selector: string, value: string): void {
  const input = fixture.nativeElement.querySelector(selector) as HTMLInputElement | HTMLSelectElement;
  input.value = value;
  input.dispatchEvent(new Event(input instanceof HTMLSelectElement ? 'change' : 'input'));
}

function submit(fixture: ComponentFixture<unknown>, formLabel: string): void {
  (fixture.nativeElement.querySelector(`form[aria-label="${formLabel}"]`) as HTMLFormElement)
    .dispatchEvent(new Event('submit'));
  fixture.detectChanges();
}

describe('PeriodFilterComponent', () => {
  let fixture: ComponentFixture<PeriodFilterComponent>;
  let emitted: PeriodFilter[];

  const tabs = () => Array.from(fixture.nativeElement.querySelectorAll('[role="tab"]')) as HTMLButtonElement[];
  const tab = (label: string) => tabs().find(button => button.textContent!.trim() === label)!;

  beforeEach(() => {
    fixture = TestBed.createComponent(PeriodFilterComponent);
    emitted = [];
    fixture.componentInstance.periodChange.subscribe(filter => emitted.push(filter));
    fixture.componentRef.setInput('value', { period: '1M', from: null, to: null });
    fixture.detectChanges();
  });

  it('offers every preset and a custom period, with the current one selected', () => {
    expect(tabs().map(button => button.textContent!.trim()))
      .toEqual(['Past week', 'Past month', 'Past 3 months', 'Year to date', 'Past year', 'Custom period']);
    expect(tab('Past month').getAttribute('aria-selected')).toBe('true');
    expect(fixture.nativeElement.querySelector('input[type="date"]')).toBeNull();
  });

  it('applies a preset as soon as it is picked', () => {
    tab('Year to date').click();
    expect(emitted).toEqual([{ period: 'YTD', from: null, to: null }]);
  });

  it('asks for days before applying a custom period', () => {
    tab('Custom period').click();
    fixture.detectChanges();
    expect(emitted).toEqual([]);
    expect(fixture.nativeElement.querySelectorAll('input[type="date"]').length).toBe(2);

    submit(fixture, 'Custom period');
    expect(emitted).toEqual([]);
    expect(fixture.nativeElement.querySelector('[role="alert"]').textContent).toContain('first and a last day');
  });

  it('refuses a custom period that runs backwards', () => {
    tab('Custom period').click();
    fixture.detectChanges();
    type(fixture, 'input[formControlName="from"]', '2026-03-01');
    type(fixture, 'input[formControlName="to"]', '2026-01-01');
    submit(fixture, 'Custom period');
    expect(emitted).toEqual([]);
    expect(fixture.nativeElement.querySelector('[role="alert"]').textContent).toContain('on or before');
  });

  it('applies a valid custom period', () => {
    tab('Custom period').click();
    fixture.detectChanges();
    type(fixture, 'input[formControlName="from"]', '2026-01-01');
    type(fixture, 'input[formControlName="to"]', '2026-03-31');
    submit(fixture, 'Custom period');
    expect(emitted).toEqual([{ period: 'CUSTOM', from: '2026-01-01', to: '2026-03-31' }]);
    expect(fixture.nativeElement.querySelector('[role="alert"]')).toBeNull();
  });

  it('shows a custom period from the URL with its days filled in', () => {
    fixture.componentRef.setInput('value', { period: 'CUSTOM', from: '2026-01-01', to: '2026-03-31' });
    fixture.detectChanges();
    expect(tab('Custom period').getAttribute('aria-selected')).toBe('true');
    expect((fixture.nativeElement.querySelector('input[formControlName="from"]') as HTMLInputElement).value).toBe('2026-01-01');
  });
});

describe('AuditFilterComponent', () => {
  let fixture: ComponentFixture<AuditFilterComponent>;
  let applied: unknown[];
  let traced: string[];

  beforeEach(() => {
    fixture = TestBed.createComponent(AuditFilterComponent);
    applied = [];
    traced = [];
    fixture.componentInstance.apply.subscribe(filter => applied.push(filter));
    fixture.componentInstance.trace.subscribe(id => traced.push(id));
    fixture.componentRef.setInput('value', EMPTY_AUDIT_FILTER);
    fixture.detectChanges();
  });

  it('offers every filter trading operations audits by', () => {
    const labels = Array.from(fixture.nativeElement.querySelectorAll('form[aria-label="Audit filters"] .filter-label'))
      .map(label => (label as HTMLElement).textContent!.trim());
    expect(labels).toEqual(['Submitted from', 'Submitted to', 'Client id', 'Account id', 'Symbol', 'Side', 'Status']);
    const statuses = Array.from(fixture.nativeElement.querySelectorAll('select[formControlName="status"] option'))
      .map(option => (option as HTMLOptionElement).value);
    expect(statuses).toEqual(['', 'SUBMITTED', 'ACCEPTED', 'REJECTED', 'FILLED']);
  });

  it('applies the filled-in fields together, tidied', () => {
    type(fixture, 'input[formControlName="from"]', '2026-10-01T09:00');
    type(fixture, 'input[formControlName="symbol"]', ' aapl ');
    type(fixture, 'select[formControlName="side"]', 'SELL');
    type(fixture, 'select[formControlName="status"]', 'REJECTED');
    submit(fixture, 'Audit filters');
    expect(applied).toEqual([{
      ...EMPTY_AUDIT_FILTER, from: '2026-10-01T09:00', symbol: 'AAPL', side: 'SELL', status: 'REJECTED'
    }]);
  });

  it('refuses a time range that runs backwards', () => {
    type(fixture, 'input[formControlName="from"]', '2026-10-08T09:00');
    type(fixture, 'input[formControlName="to"]', '2026-10-01T09:00');
    submit(fixture, 'Audit filters');
    expect(applied).toEqual([]);
    expect(fixture.nativeElement.querySelector('[role="alert"]').textContent).toContain('on or before');
  });

  it('resets every filter at once', () => {
    fixture.componentRef.setInput('value', { ...EMPTY_AUDIT_FILTER, symbol: 'AAPL', status: 'FILLED' });
    fixture.detectChanges();
    (fixture.nativeElement.querySelector('.btn-reset') as HTMLButtonElement).click();
    expect(applied).toEqual([EMPTY_AUDIT_FILTER]);
    expect((fixture.nativeElement.querySelector('input[formControlName="symbol"]') as HTMLInputElement).value).toBe('');
  });

  it('shows the filter from the URL', () => {
    fixture.componentRef.setInput('value', { ...EMPTY_AUDIT_FILTER, clientId: 'c-1', status: 'FILLED' });
    fixture.detectChanges();
    expect((fixture.nativeElement.querySelector('input[formControlName="clientId"]') as HTMLInputElement).value).toBe('c-1');
    expect((fixture.nativeElement.querySelector('select[formControlName="status"]') as HTMLSelectElement).value).toBe('FILLED');
  });

  it('traces an order by its id', () => {
    type(fixture, 'input[formControlName="orderId"]', ' 3F2B8C1E-9A4D-4C2E-8F1A-6B7D5E0C9A21 ');
    submit(fixture, 'Trace an order');
    expect(traced).toEqual(['3f2b8c1e-9a4d-4c2e-8f1a-6b7d5e0c9a21']);
    expect(applied).toEqual([]);
  });

  it('will not trace something that is not an order id', () => {
    type(fixture, 'input[formControlName="orderId"]', 'AAPL');
    submit(fixture, 'Trace an order');
    expect(traced).toEqual([]);
    expect(fixture.nativeElement.querySelector('form[aria-label="Trace an order"] [role="alert"]').textContent)
      .toContain('full order id');
  });
});
