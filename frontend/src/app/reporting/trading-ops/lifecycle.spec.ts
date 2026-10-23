import { LifecycleStage, elapsedSince, emptyLifecycle, formatDuration, fromTimeline, totalDuration, withOutcome } from './lifecycle';

describe('Order lifecycle', () => {
  const stamp = (stages: LifecycleStage[], times: Partial<Record<LifecycleStage['key'], string>>) =>
    stages.map(stage => ({ ...stage, at: times[stage.key] ?? null }));

  it('runs from submission to the reporting store, each step read from the table that records it', () => {
    const stages = emptyLifecycle();
    expect(stages.map(stage => stage.key)).toEqual(
      ['SUBMITTED', 'TRIGGERED', 'ACCEPTED', 'PRICED', 'EXECUTED', 'COMPLETED', 'CASH_SETTLED', 'HOLDINGS_UPDATED',
        'REPORTED']);
    expect(stages.map(stage => stage.source)).toEqual([
      'trading.orders.submitted_at',
      'trading.orders.triggered_at',
      'trading.orders.accepted_at',
      'trading.executions.quote_bid / quote_ask / quote_last / quote_timestamp',
      'trading.executions.executed_at',
      'trading.orders.filled_at / rejected_at / cancelled_at / expired_at',
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

  describe('orders that waited for their price', () => {
    it('names a cancelled or expired order, and marks every step of an execution as not applicable', () => {
      for (const [outcome, label, source] of [
        ['CANCELLED', 'Cancelled', 'trading.orders.cancelled_at'],
        ['EXPIRED', 'Expired', 'trading.orders.expired_at']
      ] as const) {
        const stages = withOutcome(emptyLifecycle(), outcome);
        const completed = stages.find(stage => stage.key === 'COMPLETED')!;
        expect([completed.label, completed.source]).toEqual([label, source]);
        expect(stages.filter(stage => stage.skipped).map(stage => stage.key))
          .toEqual(['ACCEPTED', 'PRICED', 'EXECUTED', 'CASH_SETTLED', 'HOLDINGS_UPDATED']);
        // It is still reported to the reporting store.
        expect(stages.find(stage => stage.key === 'REPORTED')!.skipped).toBe(false);
      }
    });

    it('skips the price-reached step for a market order, which never waits', () => {
      const stages = withOutcome(emptyLifecycle(), 'FILLED', 'MARKET');
      expect(stages.filter(stage => stage.skipped).map(stage => stage.key)).toEqual(['TRIGGERED']);
      expect(withOutcome(emptyLifecycle(), 'FILLED', 'LIMIT').some(stage => stage.skipped)).toBe(false);
      expect(withOutcome(emptyLifecycle(), null, 'MARKET').find(stage => stage.key === 'TRIGGERED')!.skipped).toBe(true);
    });
  });

  describe('fromTimeline', () => {
    const step = (type: string, at: string, description: string) => ({ type, at, description });
    const stage = (stages: LifecycleStage[], key: LifecycleStage['key']) => stages.find(s => s.key === key)!;

    it("stamps each stage from order-app's timeline, with the quote the order was priced from", () => {
      const stages = fromTimeline([
        step('SUBMITTED', '2026-10-02T10:00:00Z', 'BUY 10 AAPL placed in account ACC-1001; client was quoted $150.00'),
        step('ACCEPTED', '2026-10-02T10:00:01Z', 'Order accepted after validation'),
        step('PRICED', '2026-10-02T10:00:01.200Z', 'Market quote from NASDAQ: bid $149.95, ask $150.05, last $150.00'),
        step('EXECUTED', '2026-10-02T10:00:01.500Z', 'Executed 10 at $150.05 (execution e-1)'),
        step('CASH_SETTLED', '2026-10-02T10:00:01.600Z', 'Cash ledger BUY_SETTLEMENT: -$1500.50 USD'),
        step('POSITION_UPDATED', '2026-10-02T10:00:01.700Z', 'Holding BUY_FILL: +10 AAPL, cost +$1500.50'),
        step('FILLED', '2026-10-02T10:00:02Z', 'Order filled')
      ]);

      expect(stages.map(s => [s.key, s.at])).toEqual([
        ['SUBMITTED', '2026-10-02T10:00:00Z'],
        ['TRIGGERED', null],
        ['ACCEPTED', '2026-10-02T10:00:01Z'],
        ['PRICED', '2026-10-02T10:00:01.200Z'],
        ['EXECUTED', '2026-10-02T10:00:01.500Z'],
        ['COMPLETED', '2026-10-02T10:00:02Z'],
        ['CASH_SETTLED', '2026-10-02T10:00:01.600Z'],
        ['HOLDINGS_UPDATED', '2026-10-02T10:00:01.700Z'],
        ['REPORTED', null]
      ]);
      expect(stage(stages, 'PRICED').detail).toBe('Market quote from NASDAQ: bid $149.95, ask $150.05, last $150.00');
    });

    it('stamps a stage with its earliest step and keeps every step\'s details, one per line', () => {
      const stages = fromTimeline([
        step('PRICED', '2026-10-02T10:00:03Z', 'Second quote'),
        step('PRICED', '2026-10-02T10:00:01Z', 'First quote'),
        step('EXECUTION_REJECTED', '2026-10-02T10:00:02Z', 'Execution rejected: price moved'),
        step('EXECUTED', '2026-10-02T10:00:04Z', 'Executed 10 at $150.05')
      ]);

      expect(stage(stages, 'PRICED').at).toBe('2026-10-02T10:00:01Z');
      expect(stage(stages, 'PRICED').detail).toBe('Second quote\nFirst quote');
      expect(stage(stages, 'EXECUTED').at).toBe('2026-10-02T10:00:02Z');
    });

    it('stamps the final status from a rejection, and ignores steps it does not know', () => {
      const stages = fromTimeline([
        step('REJECTED', '2026-10-02T10:00:01Z', 'Order rejected: Insufficient cash'),
        step('SOMETHING_NEW', '2026-10-02T10:00:02Z', 'Not a stage')
      ]);

      expect(stage(stages, 'COMPLETED').detail).toBe('Order rejected: Insufficient cash');
      expect(stages.filter(s => s.at).map(s => s.key)).toEqual(['COMPLETED']);
    });

    it('leaves every stage awaiting data for an empty timeline', () => {
      expect(fromTimeline([])).toEqual(emptyLifecycle());
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
      expect(elapsedSince(stages, 3)).toBeNull();
    });

    it('shows a step stamped before the one it follows as negative', () => {
      const outOfOrder = stamp(emptyLifecycle(), {
        SUBMITTED: '2026-10-08T09:00:01.000Z',
        ACCEPTED: '2026-10-08T09:00:00.500Z'
      });
      expect(elapsedSince(outOfOrder, 2)).toBe('-500 ms');
    });

    it('has nothing to show when a timestamp cannot be read', () => {
      const unreadable = stamp(emptyLifecycle(), { SUBMITTED: 'not a date', ACCEPTED: '2026-10-08T09:00:00.500Z' });
      expect(elapsedSince(unreadable, 2)).toBeNull();
    });

    it('measures from the previous step', () => {
      expect(elapsedSince(stages, 2)).toBe('+250 ms');
    });

    it('measures from the last recorded step when the one before is missing', () => {
      expect(elapsedSince(stages, 5)).toBe('+2.0 s');
      expect(elapsedSince(stages, 8)).toBe('+5 min');
    });

    it('shows a step recorded before the one ahead of it as negative', () => {
      const outOfOrder = stamp(emptyLifecycle(), { SUBMITTED: '2026-10-08T09:00:01Z', ACCEPTED: '2026-10-08T09:00:00Z' });
      expect(elapsedSince(outOfOrder, 2)).toBe('-1.0 s');
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
