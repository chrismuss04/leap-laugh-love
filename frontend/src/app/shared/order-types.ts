import { OpenOrder, OrderSide, OrderType, TimeInForce } from '../services/order';
import { formatMoney } from './format';

/** The order types the ticket offers, in the order it shows them. */
export const ORDER_TYPES: { type: OrderType; label: string }[] = [
  { type: 'MARKET', label: 'Market' },
  { type: 'LIMIT', label: 'Limit' },
  { type: 'STOP', label: 'Stop' },
  { type: 'STOP_LIMIT', label: 'Stop limit' }
];

/**
 * A stop buy becomes a market order, which can fill above its stop if the price jumps, so order-app
 * holds this much more than stop x quantity (see ReservationService.STOP_BUY_BUFFER).
 */
export const STOP_BUY_BUFFER = 1.05;

/** "Limit", "Stop limit"... */
export function orderTypeLabel(type: OrderType | null | undefined): string {
  return ORDER_TYPES.find(t => t.type === (type ?? 'MARKET'))?.label ?? 'Market';
}

/** One line on what the type does for this side, shown under the type picker. */
export function orderTypeHint(type: OrderType, side: OrderSide): string {
  const buy = side === 'BUY';
  switch (type) {
    case 'MARKET':
      return 'Fills right away at the best available price.';
    case 'LIMIT':
      return buy ? 'Buys only at your limit price or lower.' : 'Sells only at your limit price or higher.';
    case 'STOP':
      return buy
        ? 'Buys at the market price once the price rises to your stop.'
        : 'A stop loss: sells at the market price if the price falls to your stop.';
    case 'STOP_LIMIT':
      return buy
        ? 'Once the price rises to your stop, buys at your limit price or lower.'
        : 'Once the price falls to your stop, sells at your limit price or higher.';
  }
}

export function timeInForceLabel(timeInForce: TimeInForce | null | undefined): string {
  return timeInForce === 'GTC' ? 'Until cancelled' : 'Good for day';
}

/** What the order holds back per share while open: its limit, or its stop plus the stop-buy buffer. */
export function reservePricePerShare(type: OrderType, limitPrice: number | null, stopPrice: number | null): number | null {
  if (type === 'LIMIT' || type === 'STOP_LIMIT') {
    return limitPrice;
  }
  if (type === 'STOP') {
    return stopPrice == null ? null : stopPrice * STOP_BUY_BUFFER;
  }
  return null;
}

/** How far a symbol's ask and bid sit from its last trade, from its latest quote. */
export interface QuoteOffset {
  ask: number;
  bid: number;
}

/** A piece of the order sentence; strong pieces are the numbers that matter. */
export interface SentencePart {
  text: string;
  strong?: boolean;
}

export interface OrderDescription {
  side: OrderSide;
  type: OrderType;
  symbol: string;
  quantity: number | null;
  limitPrice: number | null;
  stopPrice: number | null;
  timeInForce: TimeInForce;
}

/**
 * The order in plain English, e.g. "If AAPL falls to $140.00, sell 10 shares at the market price,
 * good until cancelled." Updated live as the ticket is edited, so stop vs stop limit is clear
 * without the jargon.
 */
export function describeOrder(order: OrderDescription): SentencePart[] {
  const verb = order.side === 'BUY' ? 'buy' : 'sell';
  const shares = order.quantity != null && order.quantity >= 1
    ? `${order.quantity.toLocaleString('en-US')} ${order.quantity === 1 ? 'share' : 'shares'}`
    : 'shares';
  const limit = order.limitPrice != null ? formatMoney(order.limitPrice) : '$—';
  const stop = order.stopPrice != null ? formatMoney(order.stopPrice) : '$—';
  const orBetter = order.side === 'BUY' ? 'or lower' : 'or higher';
  const moves = order.side === 'BUY' ? 'rises' : 'falls';
  const until: SentencePart = {
    text: order.timeInForce === 'GTC' ? ', good until cancelled.' : ', good for today.'
  };

  switch (order.type) {
    case 'MARKET':
      return [
        { text: `${capitalise(verb)} ` }, { text: shares, strong: true },
        { text: ` of ${order.symbol} now at the best available price.` }
      ];
    case 'LIMIT':
      return [
        { text: `${capitalise(verb)} ` }, { text: shares, strong: true }, { text: ` of ${order.symbol} at ` },
        { text: limit, strong: true }, { text: ` ${orBetter}` }, until
      ];
    case 'STOP':
      return [
        { text: `If ${order.symbol} ${moves} to ` }, { text: stop, strong: true }, { text: `, ${verb} ` },
        { text: shares, strong: true }, { text: ' at the market price' }, until
      ];
    case 'STOP_LIMIT':
      return [
        { text: `If ${order.symbol} ${moves} to ` }, { text: stop, strong: true }, { text: `, ${verb} ` },
        { text: shares, strong: true }, { text: ' at ' }, { text: limit, strong: true },
        { text: ` ${orBetter}` }, until
      ];
  }
}

export function sentenceText(parts: SentencePart[]): string {
  return parts.map(p => p.text).join('');
}

/** The price an open order is waiting for: its stop until that's reached, then its limit. */
export function waitingPrice(order: Pick<OpenOrder, 'orderType' | 'limitPrice' | 'stopPrice' | 'triggeredAt'>): number | null {
  if (order.orderType === 'STOP' || (order.orderType === 'STOP_LIMIT' && !order.triggeredAt)) {
    return order.stopPrice;
  }
  return order.limitPrice;
}

/**
 * Which way the price an open order fills at must move to reach the price it waits for: a buy
 * limit (or a triggered buy stop limit) and a sell stop wait for it to fall, the others to rise.
 */
export function requiredMove(order: Pick<OpenOrder, 'orderType' | 'side' | 'triggeredAt'>): 'rise' | 'fall' {
  const onStop = order.orderType === 'STOP' || (order.orderType === 'STOP_LIMIT' && !order.triggeredAt);
  const buy = order.side === 'BUY';
  return onStop === buy ? 'rise' : 'fall';
}

/** The quote side an order fills at: buys pay the ask, sells get the bid. */
export function quoteSide(side: OrderSide): 'Ask' | 'Bid' {
  return side === 'BUY' ? 'Ask' : 'Bid';
}

/**
 * Which way and how far the market must move to reach a price, from the side of the order: a buy
 * limit waits for the price to fall to it, a sell stop for it to fall, a buy stop to rise...
 * Returns null without a price, and 0 once the price is there.
 */
export function distanceTo(target: number | null, price: number | null): { percent: number; direction: 'rise' | 'fall' } | null {
  if (target == null || price == null || price <= 0) {
    return null;
  }
  const percent = ((target - price) / price) * 100;
  return { percent: Math.abs(percent), direction: percent >= 0 ? 'rise' : 'fall' };
}

/**
 * "3.2% below the current price" / "At the current price". `reference` names the price measured
 * from, e.g. "the ask" for a buy, which fills at the ask rather than the last trade.
 */
export function distanceLabel(target: number | null, price: number | null, reference = 'the current price'): string | null {
  const distance = distanceTo(target, price);
  if (!distance) {
    return null;
  }
  if (distance.percent < 0.005) {
    return `At ${reference}`;
  }
  const digits = distance.percent < 1 ? 2 : distance.percent < 10 ? 1 : 0;
  return `${distance.percent.toFixed(digits)}% ${distance.direction === 'rise' ? 'above' : 'below'} ${reference}`;
}

/** Rounds to cents, for prices picked from chips. */
export function toCents(value: number): number {
  return Math.round(value * 100) / 100;
}

/** What each account's open orders hold back: cash for buys, shares per symbol for sells. */
export interface AccountHolds {
  cash: number;
  shares: Record<string, number>;
}

export function holdsByAccount(orders: OpenOrder[]): Record<string, AccountHolds> {
  const holds: Record<string, AccountHolds> = {};
  for (const order of orders) {
    const account = holds[order.accountId] ??= { cash: 0, shares: {} };
    if (order.side === 'BUY') {
      account.cash += order.reservedCash ?? 0;
    } else {
      account.shares[order.symbol] = (account.shares[order.symbol] ?? 0) + order.quantity;
    }
  }
  return holds;
}

/** "Limit buy", "Stop sell"... */
export function openOrderTitle(order: Pick<OpenOrder, 'orderType' | 'side'>): string {
  return `${orderTypeLabel(order.orderType)} ${order.side === 'BUY' ? 'buy' : 'sell'}`;
}

function capitalise(text: string): string {
  return text.charAt(0).toUpperCase() + text.slice(1);
}
