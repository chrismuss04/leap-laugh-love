import { TestBed } from '@angular/core/testing';
import { LifecycleTimelineComponent } from './lifecycle-timeline';
import { LifecycleStage, emptyLifecycle, withOutcome } from './lifecycle';

describe('LifecycleTimelineComponent', () => {
  const render = (stages: LifecycleStage[]) => {
    const fixture = TestBed.createComponent(LifecycleTimelineComponent);
    fixture.componentRef.setInput('lifecycle', stages);
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  };
  const rows = (el: HTMLElement) => Array.from(el.querySelectorAll('[data-testid="lifecycle-stage"]')) as HTMLElement[];
  const row = (el: HTMLElement, key: string) => el.querySelector(`[data-stage="${key}"]`) as HTMLElement;

  it('lists every step in order, each awaiting data until the trace is loaded', () => {
    const el = render(emptyLifecycle());
    expect(rows(el).map(r => r.querySelector('.stage-label')!.textContent)).toEqual(
      ['Submitted', 'Accepted', 'Executed', 'Filled or rejected', 'Cash settled', 'Holdings updated', 'Reported']);
    expect(rows(el).every(r => r.dataset['state'] === 'awaiting')).toBeTrue();
    expect(el.querySelector('time')).toBeNull();
    expect(el.querySelector('.total')).toBeNull();
  });

  it('names the table each timestamp comes from', () => {
    const el = render(emptyLifecycle());
    expect(row(el, 'CASH_SETTLED').querySelector('code')!.textContent).toBe('trading.cash_ledger.created_at');
    expect(row(el, 'HOLDINGS_UPDATED').querySelector('code')!.textContent).toBe('trading.position_movements.created_at');
  });

  it('shows each recorded timestamp to the millisecond, and the time since the step before', () => {
    const stages = emptyLifecycle().map(stage =>
      stage.key === 'SUBMITTED' ? { ...stage, at: '2026-10-08T09:00:00.000Z' }
        : stage.key === 'ACCEPTED' ? { ...stage, at: '2026-10-08T09:00:00.125Z' }
          : stage);
    const el = render(stages);

    const accepted = row(el, 'ACCEPTED');
    expect(accepted.dataset['state']).toBe('done');
    expect(accepted.querySelector('time')!.getAttribute('datetime')).toBe('2026-10-08T09:00:00.125Z');
    expect(accepted.querySelector('time')!.textContent).toMatch(/\d{4}-\d{2}-\d{2} \d{2}:\d{2}:00\.125/);
    expect(accepted.querySelector('.elapsed')!.textContent).toBe('+125 ms');
    expect(row(el, 'SUBMITTED').querySelector('.elapsed')).toBeNull();
    expect(el.querySelector('.total')!.textContent).toContain('125 ms');
  });

  it('shows the details recorded with a step', () => {
    const stages = withOutcome(emptyLifecycle(), 'REJECTED').map(stage =>
      stage.key === 'COMPLETED' ? { ...stage, at: '2026-10-08T09:00:01Z', detail: 'Insufficient buying power' } : stage);
    expect(row(render(stages), 'COMPLETED').textContent).toContain('Insufficient buying power');
  });

  it('marks cash and holdings as not applicable for a rejected order', () => {
    const el = render(withOutcome(emptyLifecycle(), 'REJECTED'));
    expect(row(el, 'COMPLETED').querySelector('.stage-label')!.textContent).toBe('Rejected');
    for (const key of ['CASH_SETTLED', 'HOLDINGS_UPDATED']) {
      expect(row(el, key).dataset['state']).toBe('skipped');
      expect(row(el, key).textContent).toContain('Not applicable');
    }
    expect(row(el, 'REPORTED').dataset['state']).toBe('awaiting');
  });
});
