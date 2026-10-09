import { LifecycleStage, elapsedSince, emptyLifecycle, formatDuration, totalDuration, withOutcome } from './lifecycle';

describe('Order lifecycle', () => {
  const stamp = (stages: LifecycleStage[], times: Partial<Record<LifecycleStage['key'], string>>) =>
    stages.map(stage => ({ ...stage, at: times[stage.key] ?? null }));

  it('runs from submission to the reporting store, each step read from the table that records it', () => {
    const stages = emptyLifecycle();
    expect(stages.map(stage => stage.key)).toEqual(
      ['SUBMITTED', 'ACCEPTED', 'EXECUTED', 'COMPLETED', 'CASH_SETTLED', 'HOLDINGS_UPDATED', 'REPORTED']);
    expect(stages.map(stage => stage.source)).toEqual([
      'trading.orders.submitted_at',
      'trading.orders.accepted_at',
      'trading.executions.executed_at',
      'trading.orders.filled_at / rejected_at',
      'trading.cash_ledger.created_at',
      'trading.position_movements.created_at',
      'reporting.orders.loaded_at'
    ]);
  });

  it('starts with nothing recorded', () => {
    expect(emptyLifecycle().every(stage => stage.at === null && stage.detail === null && !stage.skipped)).toBe(true);
  });

  it('returns fresh stages each time, so one trace cannot change another', () => {
    const first = emptyLifecycle();
    first[0].at = '2026-10-08T09:00:00Z';
    expect(emptyLifecycle()[0].at).toBeNull();
  });

  describe('withOutcome', () => {
    it('leaves the stages alone while the outcome is unknown', () => {
      const stages = emptyLifecycle();
      expect(withOutcome(stages, null)).toBe(stages);
    });

    it('names a filled order and keeps settlement', () => {
      const stages = withOutcome(emptyLifecycle(), 'FILLED');
      const completed = stages.find(stage => stage.key === 'COMPLETED')!;
      expect(completed.label).toBe('Filled');
      expect(completed.source).toBe('trading.orders.filled_at');
      expect(stages.some(stage => stage.skipped)).toBe(false);
    });

    it('marks cash and holdings as not applicable for a rejected order, but still reports it', () => {
      const stages = withOutcome(emptyLifecycle(), 'REJECTED');
      expect(stages.find(stage => stage.key === 'COMPLETED')!.label).toBe('Rejected');
      expect(stages.filter(stage => stage.skipped).map(stage => stage.key)).toEqual(['CASH_SETTLED', 'HOLDINGS_UPDATED']);
      expect(stages.find(stage => stage.key === 'REPORTED')!.skipped).toBe(false);
    });
  });

  describe('elapsedSince', () => {
    const stages = stamp(emptyLifecycle(), {
      SUBMITTED: '2026-10-08T09:00:00.000Z',
      ACCEPTED: '2026-10-08T09:00:00.250Z',
      COMPLETED: '2026-10-08T09:00:02.250Z',
      REPORTED: '2026-10-08T09:05:02.250Z'
    });

    it('has nothing to measure for the first step or a step not yet recorded', () => {
      expect(elapsedSince(stages, 0)).toBeNull();
      expect(elapsedSince(stages, 2)).toBeNull();
    });

    it('measures from the previous step', () => {
      expect(elapsedSince(stages, 1)).toBe('+250 ms');
    });

    it('measures from the last recorded step when the one before is missing', () => {
      expect(elapsedSince(stages, 3)).toBe('+2.0 s');
      expect(elapsedSince(stages, 6)).toBe('+5 min');
    });

    it('shows a step recorded before the one ahead of it as negative', () => {
      const outOfOrder = stamp(emptyLifecycle(), { SUBMITTED: '2026-10-08T09:00:01Z', ACCEPTED: '2026-10-08T09:00:00Z' });
      expect(elapsedSince(outOfOrder, 1)).toBe('-1.0 s');
    });
  });

  it('measures the whole lifecycle from first to last recorded step', () => {
    expect(totalDuration(emptyLifecycle())).toBeNull();
    expect(totalDuration(stamp(emptyLifecycle(), {
      SUBMITTED: '2026-10-08T09:00:00Z', REPORTED: '2026-10-08T10:30:00Z'
    }))).toBe('1.5 h');
  });

  it('formats durations at a readable scale', () => {
    expect(formatDuration(12)).toBe('12 ms');
    expect(formatDuration(1500)).toBe('1.5 s');
    expect(formatDuration(90_000)).toBe('2 min');
    expect(formatDuration(2 * 86_400_000)).toBe('2.0 d');
  });
});
