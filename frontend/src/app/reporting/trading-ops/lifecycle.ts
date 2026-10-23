import { TimelineStep } from '../../services/trade-ops';

/**
 * Staff Dashboards: the steps an order goes through, from submission to the reporting store, each
 * stamped by the table that records it. Trading operations retraces an order by reading these in
 * order; the time between steps shows where an order waited.
 */
export type StageKey =
  | 'SUBMITTED' | 'TRIGGERED' | 'ACCEPTED' | 'PRICED' | 'EXECUTED' | 'COMPLETED' | 'CASH_SETTLED' | 'HOLDINGS_UPDATED'
  | 'REPORTED';

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

// [key, label, description, source] - a table rather than object literals, so the nine
// stages read as one block.
const STAGE_ROWS: readonly (readonly [StageKey, string, string, string])[] = [
  ['SUBMITTED', 'Submitted',
    'The client placed the order.',
    'trading.orders.submitted_at'],
  ['TRIGGERED', 'Price reached',
    'A limit, stop or trailing order waited OPEN until the market reached its price.',
    'trading.orders.triggered_at'],
  ['ACCEPTED', 'Accepted',
    'The order passed validation and was queued for execution.',
    'trading.orders.accepted_at'],
  ['PRICED', 'Priced',
    'The market quote the order was priced from: its bid, ask and last price, and the price this side fills at.',
    'trading.executions.quote_bid / quote_ask / quote_last / quote_timestamp'],
  ['EXECUTED', 'Executed',
    'The execution engine filled or rejected the order.',
    'trading.executions.executed_at'],
  ['COMPLETED', 'Filled or rejected',
    'The order reached its final status: filled, rejected, cancelled or expired.',
    'trading.orders.filled_at / rejected_at / cancelled_at / expired_at'],
  ['CASH_SETTLED', 'Cash settled',
    'The account was debited for a buy or credited for a sale.',
    'trading.cash_ledger.created_at'],
  ['HOLDINGS_UPDATED', 'Holdings updated',
    'The position moved by the filled quantity.',
    'trading.position_movements.created_at'],
  ['REPORTED', 'Reported',
    'The reporting pipeline loaded the finished order.',
    'reporting.orders.loaded_at']
];

const STAGES: readonly Pick<LifecycleStage, 'key' | 'label' | 'description' | 'source'>[] =
  STAGE_ROWS.map(([key, label, description, source]) => ({ key, label, description, source }));

/** Every stage with nothing recorded yet: the trace's shape before its data is loaded. */
export function emptyLifecycle(): LifecycleStage[] {
  return STAGES.map(stage => ({ ...stage, at: null, detail: null, skipped: false }));
}

/** The stage each of order-app's timeline steps stamps. */
const STEP_STAGES: Record<string, StageKey> = {
  SUBMITTED: 'SUBMITTED',
  ACCEPTED: 'ACCEPTED',
  PRICED: 'PRICED',
  EXECUTED: 'EXECUTED',
  EXECUTION_REJECTED: 'EXECUTED',
  FILLED: 'COMPLETED',
  REJECTED: 'COMPLETED',
  CASH_SETTLED: 'CASH_SETTLED',
  POSITION_UPDATED: 'HOLDINGS_UPDATED'
};

/**
 * The stages stamped from order-app's timeline. A stage takes its earliest step's time, and the
 * details of every step that stamps it, one per line - an order filled in parts is priced and
 * executed more than once. Steps for stages order-app doesn't record (price reached, reported)
 * leave them awaiting data, and steps of unknown types are ignored.
 */
export function fromTimeline(steps: TimelineStep[]): LifecycleStage[] {
  return emptyLifecycle().map(stage => {
    const stamped = steps.filter(step => STEP_STAGES[step.type] === stage.key);
    if (!stamped.length) { return stage; }
    const earliest = stamped.reduce((first, step) => Date.parse(step.at) < Date.parse(first.at) ? step : first);
    return { ...stage, at: earliest.at, detail: stamped.map(step => step.description).join('\n') };
  });
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
  if (!outcome && !orderType) { return stages; }
  const executed = outcome !== 'CANCELLED' && outcome !== 'EXPIRED';
  return stages.map(stage => {
    if (stage.key === 'TRIGGERED' && orderType === 'MARKET') {
      return { ...stage, skipped: true };
    }
    if (!outcome) { return stage; }
    if (stage.key === 'COMPLETED') {
      return { ...stage, ...COMPLETED_LABELS[outcome] };
    }
    if (outcome === 'REJECTED' && (stage.key === 'CASH_SETTLED' || stage.key === 'HOLDINGS_UPDATED')) {
      return { ...stage, skipped: true };
    }
    if (!executed && ['ACCEPTED', 'PRICED', 'EXECUTED', 'CASH_SETTLED', 'HOLDINGS_UPDATED'].includes(stage.key)) {
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
  if (!at) { return null; }
  const previous = stages.slice(0, index).reverse().find(stage => stage.at && !stage.skipped);
  if (!previous) { return null; }
  const ms = Date.parse(at) - Date.parse(previous.at!);
  if (Number.isNaN(ms)) { return null; }
  return (ms < 0 ? '-' : '+') + formatDuration(Math.abs(ms));
}

/** The time from the first to the last recorded stage, or null with fewer than two. */
export function totalDuration(stages: LifecycleStage[]): string | null {
  const times = stages.filter(stage => stage.at && !stage.skipped).map(stage => Date.parse(stage.at!));
  if (times.length < 2 || times.some(Number.isNaN)) { return null; }
  return formatDuration(Math.max(...times) - Math.min(...times));
}

export function formatDuration(ms: number): string {
  if (ms < 1000) { return `${Math.round(ms)} ms`; }
  if (ms < 60_000) { return `${(ms / 1000).toFixed(1)} s`; }
  if (ms < 3_600_000) { return `${Math.round(ms / 60_000)} min`; }
  if (ms < 86_400_000) { return `${(ms / 3_600_000).toFixed(1)} h`; }
  return `${(ms / 86_400_000).toFixed(1)} d`;
}
