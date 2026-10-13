/**
 * Staff Dashboards: the steps an order goes through, from submission to the reporting store, each
 * stamped by the table that records it. Trading operations retraces an order by reading these in
 * order; the time between steps shows where an order waited.
 */
export type StageKey =
  | 'SUBMITTED' | 'TRIGGERED' | 'ACCEPTED' | 'EXECUTED' | 'COMPLETED' | 'CASH_SETTLED' | 'HOLDINGS_UPDATED' | 'REPORTED';

export interface LifecycleStage {
  key: StageKey;
  label: string;
  /** What happens at this step. */
  description: string;
  /** The column the timestamp comes from. */
  source: string;
  /** When it happened (ISO 8601), or null if it hasn't, or the data isn't loaded yet. */
  at: string | null;
  /** Extra detail recorded with the step, e.g. a rejection reason or ledger entry type. */
  detail: string | null;
  /** True when the step can't happen for this order, e.g. settlement after a rejection. */
  skipped: boolean;
}

export type OrderOutcome = 'FILLED' | 'REJECTED' | 'CANCELLED' | 'EXPIRED';

const STAGES: readonly Pick<LifecycleStage, 'key' | 'label' | 'description' | 'source'>[] = [
  {
    key: 'SUBMITTED', label: 'Submitted',
    description: 'The client placed the order.',
    source: 'trading.orders.submitted_at'
  },
  {
    key: 'TRIGGERED', label: 'Price reached',
    description: 'A limit, stop or trailing order waited OPEN until the market reached its price.',
    source: 'trading.orders.triggered_at'
  },
  {
    key: 'ACCEPTED', label: 'Accepted',
    description: 'The order passed validation and was queued for execution.',
    source: 'trading.orders.accepted_at'
  },
  {
    key: 'EXECUTED', label: 'Executed',
    description: 'The execution engine filled or rejected the order.',
    source: 'trading.executions.executed_at'
  },
  {
    key: 'COMPLETED', label: 'Filled or rejected',
    description: 'The order reached its final status: filled, rejected, cancelled or expired.',
    source: 'trading.orders.filled_at / rejected_at / cancelled_at / expired_at'
  },
  {
    key: 'CASH_SETTLED', label: 'Cash settled',
    description: 'The account was debited for a buy or credited for a sale.',
    source: 'trading.cash_ledger.created_at'
  },
  {
    key: 'HOLDINGS_UPDATED', label: 'Holdings updated',
    description: 'The position moved by the filled quantity.',
    source: 'trading.position_movements.created_at'
  },
  {
    key: 'REPORTED', label: 'Reported',
    description: 'The reporting pipeline loaded the finished order.',
    source: 'reporting.orders.loaded_at'
  }
];

/** Every stage with nothing recorded yet: the trace's shape before its data is loaded. */
export function emptyLifecycle(): LifecycleStage[] {
  return STAGES.map(stage => ({ ...stage, at: null, detail: null, skipped: false }));
}

const COMPLETED_LABELS: Record<OrderOutcome, { label: string; source: string }> = {
  FILLED: { label: 'Filled', source: 'trading.orders.filled_at' },
  REJECTED: { label: 'Rejected', source: 'trading.orders.rejected_at' },
  CANCELLED: { label: 'Cancelled', source: 'trading.orders.cancelled_at' },
  EXPIRED: { label: 'Expired', source: 'trading.orders.expired_at' }
};

/**
 * Names the final status, and marks the steps that can't happen as not applicable: a rejected
 * order moves no cash and no holdings, but is still reported; a cancelled or expired one never
 * executed at all; and a market order fills at once, so it never waits for its price.
 * @param orderType the order's type, once known; a market order skips the price-reached step
 */
export function withOutcome(stages: LifecycleStage[], outcome: OrderOutcome | null, orderType?: string | null): LifecycleStage[] {
  if (!outcome && !orderType) return stages;
  const executed = outcome !== 'CANCELLED' && outcome !== 'EXPIRED';
  return stages.map(stage => {
    if (stage.key === 'TRIGGERED' && orderType === 'MARKET') {
      return { ...stage, skipped: true };
    }
    if (!outcome) return stage;
    if (stage.key === 'COMPLETED') {
      return { ...stage, ...COMPLETED_LABELS[outcome] };
    }
    if (outcome === 'REJECTED' && (stage.key === 'CASH_SETTLED' || stage.key === 'HOLDINGS_UPDATED')) {
      return { ...stage, skipped: true };
    }
    if (!executed && ['ACCEPTED', 'EXECUTED', 'CASH_SETTLED', 'HOLDINGS_UPDATED'].includes(stage.key)) {
      return { ...stage, skipped: true };
    }
    return stage;
  });
}

/**
 * Time since the most recent earlier stage that has a timestamp, e.g. "+250 ms", "+1.4 s",
 * "+3 min" - or null when there's nothing to measure from.
 */
export function elapsedSince(stages: LifecycleStage[], index: number): string | null {
  const at = stages[index]?.at;
  if (!at) return null;
  const previous = stages.slice(0, index).reverse().find(stage => stage.at && !stage.skipped);
  if (!previous) return null;
  const ms = Date.parse(at) - Date.parse(previous.at!);
  if (Number.isNaN(ms)) return null;
  return (ms < 0 ? '-' : '+') + formatDuration(Math.abs(ms));
}

/** The time from the first to the last recorded stage, or null with fewer than two. */
export function totalDuration(stages: LifecycleStage[]): string | null {
  const times = stages.filter(stage => stage.at && !stage.skipped).map(stage => Date.parse(stage.at!));
  if (times.length < 2 || times.some(Number.isNaN)) return null;
  return formatDuration(Math.max(...times) - Math.min(...times));
}

export function formatDuration(ms: number): string {
  if (ms < 1000) return `${Math.round(ms)} ms`;
  if (ms < 60_000) return `${(ms / 1000).toFixed(1)} s`;
  if (ms < 3_600_000) return `${Math.round(ms / 60_000)} min`;
  if (ms < 86_400_000) return `${(ms / 3_600_000).toFixed(1)} h`;
  return `${(ms / 86_400_000).toFixed(1)} d`;
}
