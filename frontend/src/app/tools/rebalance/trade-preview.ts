import { PlanQuoteOffset, PlanSide, PlanWarning, PlannedOrder, planOrders } from '../../shared/order-plan';
import { DonutSlice } from './allocation';
import { WHOLE } from './targets';

/** Every slice within this much of its target counts as balanced: no trades are worth making. */
export const BALANCED_WITHIN = 0.01;
/** The engine keeps this share of the buys' cost as headroom (see DEFAULT_CASH_BUFFER). */
const CASH_HEADROOM_TENTHS = 5;

export interface PreviewInput {
  slices: Pick<DonutSlice, 'key' | 'kind' | 'share'>[];
  /** Each slice's target, in tenths of a percent. */
  targets: Record<string, number>;
  positions: { symbol: string; quantity: number }[];
  prices: Record<string, number>;
  quoteOffsets?: Record<string, PlanQuoteOffset>;
  cash: number;
}

export interface PreviewWarning extends PlanWarning {
  /** What the client can do about it, when there's something. */
  hint: string | null;
}

/** The trade a slice needs, for its badge. */
export interface SliceTrade {
  side: PlanSide;
  quantity: number;
  value: number;
}

export interface TradePreview {
  /** Sells, then buys, each by symbol: the order they'd be placed in. Empty when balanced. */
  orders: PlannedOrder[];
  warnings: PreviewWarning[];
  /** How far current is from target: the share (0-1) of the account that would change hands. */
  drift: number;
  /** Every slice is within 1% of its target. */
  balanced: boolean;
  bySymbol: Record<string, SliceTrade>;
  /** Cash once every trade fills at its quoted price. */
  cashAfter: number;
  buyTotal: number;
  sellTotal: number;
}

/**
 * Rebalance Donut: the trades the client's targets take, from the shared order-plan engine. Each
 * holding's target goes to the engine as a percentage of the account; cash is what's left over.
 * Cheap enough to run on every step of a drag.
 */
export function previewTrades(input: PreviewInput): TradePreview {
  const gaps = input.slices.map(s => Math.abs(s.share - (input.targets[s.key] ?? 0) / WHOLE));
  const drift = gaps.reduce((a, b) => a + b, 0) / 2;
  const balanced = gaps.every(gap => gap <= BALANCED_WITHIN + 1e-9);
  if (balanced) {
    return { orders: [], warnings: [], drift, balanced, bySymbol: {}, cashAfter: input.cash, buyTotal: 0, sellTotal: 0 };
  }

  const plan = planOrders({
    positions: input.positions,
    prices: input.prices,
    quoteOffsets: input.quoteOffsets,
    cash: input.cash,
    targets: input.slices.filter(s => s.kind === 'holding').map(s => ({ symbol: s.key, percent: (input.targets[s.key] ?? 0) / 10 }))
  });

  const cashTarget = input.targets['cash'] ?? 0;
  const warnings = plan.warnings.map(warning => ({
    ...warning,
    hint: warning.code === 'not-enough-cash' && cashTarget < CASH_HEADROOM_TENTHS
      ? 'Buys keep a 0.5% cash cushion for prices moving before they fill. Target at least 0.5% cash to buy in full.'
      : null
  }));
  const bySymbol: Record<string, SliceTrade> = {};
  for (const order of plan.orders) {
    bySymbol[order.symbol] = { side: order.side, quantity: order.quantity, value: order.estimatedValue };
  }
  const total = (side: PlanSide) => plan.orders.filter(o => o.side === side).reduce((sum, o) => sum + o.estimatedValue, 0);
  return {
    orders: plan.orders, warnings, drift, balanced, bySymbol, cashAfter: plan.cashAfter,
    buyTotal: Math.round(total('BUY') * 100) / 100, sellTotal: Math.round(total('SELL') * 100) / 100
  };
}
