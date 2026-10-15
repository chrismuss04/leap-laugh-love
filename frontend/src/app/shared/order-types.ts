import { OpenOrder, OrderSide, OrderType, TimeInForce } from '../services/order';
import { formatMoney } from './format';

/** The order types the ticket offers, in the order it shows them. */
export const ORDER_TYPES: { type: OrderType; label: string }[] = [
  { type: 'MARKET', label: 'Market' },
  { type: 'LIMIT', label: 'Limit' },
  { type: 'STOP', label: 'Stop' },
  { type: 'STOP_LIMIT', label: 'Stop limit' },
  { type: 'TRAILING_STOP', label: 'Trailing stop' }
];

/**
 * A stop buy becomes a market order, which can fill above its stop if the price jumps, so order-app
 * holds this much more than stop x quantity (see ReservationService.STOP_BUY_BUFFER).
 */
export const STOP_BUY_BUFFER = 1.05;

/**
 * How far a market order queued for the open may fill from the price when it was placed: a buy pays
 * at most 5% more, a sell takes at least 5% less (see order-app's ReservationService).
 */
export const QUEUED_MARKET_COLLAR = 0.05;

/** A queued market order's collar: 5% above the ask for a buy, 5% below the bid for a sell. */
export function queuedCollar(side: OrderSide, price: number | null): number | null {
  if (price == null) {
    return null;
  }
  return side === 'BUY' ? price * (1 + QUEUED_MARKET_COLLAR) : price * (1 - QUEUED_MARKET_COLLAR);
}

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
    case 'TRAILING_STOP':
      return buy
        ? 'A stop that follows the price down: buys if it rises your trail off its low.'
        : 'A stop that rises with the price: sells if it falls your trail from its high, locking in gains.';
  }
}

export function timeInForceLabel(timeInForce: TimeInForce | null | undefined): string {
  return timeInForce === 'GTC' ? 'Until cancelled' : 'Good for day';
}

/**
 * What the order holds back per share while open: its limit, its stop plus the stop-buy buffer, or,
 * for a market order queued for the open, its collar.
 */
export function reservePricePerShare(type: OrderType, limitPrice: number | null, stopPrice: number | null,
                                     collarPrice: number | null = null): number | null {
  if (type === 'MARKET') {
    return collarPrice;
  }
  if (type === 'LIMIT' || type === 'STOP_LIMIT') {
    return limitPrice;
  }
  // A trailing stop holds what it would at its starting stop; the stop only moves in its favour.
  if (type === 'STOP' || type === 'TRAILING_STOP') {
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
  /** For a trailing stop: the trail, in dollars or percent. */
  trailAmount?: number | null;
  trailPercent?: number | null;
  /** A buy's bracket: the sells that open when it fills. */
  bracketTakeProfit?: number | null;
  bracketStopLoss?: number | null;
  /** For a limit order: whether it also trades in pre-market and after-hours. */
  extendedHours?: boolean;
  /** For a market order placed outside regular hours: when the open it waits for is, e.g. "Mon 9:30 AM ET". */
  queuedFor?: string | null;
}

/** "$5.00" or "2%": how far a trailing stop trails, or "$—" before it's entered. */
export function trailText(amount: number | null | undefined, percent: number | null | undefined): string {
  if (percent != null) {
    return `${+percent.toFixed(2)}%`;
  }
  return amount != null ? formatMoney(amount) : '$—';
}

/**
 * The order in plain English, e.g. "If AAPL falls to $140.00, sell 10 shares at the market price,
 * good until cancelled." Updated live as the ticket is edited, so stop vs stop limit is clear
 * without the jargon.
 */
export function describeOrder(order: OrderDescription): SentencePart[] {
  const entry = describeEntry(order);
  if (order.side !== 'BUY' || order.bracketTakeProfit == null || order.bracketStopLoss == null) {
    return entry;
  }
  return [
    ...entry,
    { text: ' Once it fills, sell to take profit at ' }, { text: formatMoney(order.bracketTakeProfit), strong: true },
    { text: ' or to cut losses at ' }, { text: formatMoney(order.bracketStopLoss), strong: true },
    { text: ' — whichever comes first cancels the other.' }
  ];
}

/** "10 shares", "1 share", or "shares" before a quantity is entered. */
function sharesText(quantity: number | null): string {
  if (quantity == null || quantity < 1) {
    return 'shares';
  }
  return `${quantity.toLocaleString('en-US')} ${quantity === 1 ? 'share' : 'shares'}`;
}

/** ", good for today, including extended hours.": how long the order works, and when. */
function untilPart(order: OrderDescription): SentencePart {
  const until = order.timeInForce === 'GTC' ? ', good until cancelled' : ', good for today';
  const extended = order.type === 'LIMIT' && order.extendedHours ? ', including extended hours' : '';
  return { text: until + extended + '.' };
}

/** A market order: now, or queued for the open when the market isn't in regular hours. */
function describeMarket(order: OrderDescription, verb: string, shares: string): SentencePart[] {
  const start: SentencePart[] = [{ text: `${capitalise(verb)} ` }, { text: shares, strong: true }];
  if (order.queuedFor === undefined) {
    return [...start, { text: ` of ${order.symbol} now at the best available price.` }];
  }
  const when: SentencePart[] = order.queuedFor
    ? [{ text: ' (' }, { text: order.queuedFor, strong: true }, { text: ')' }]
    : [];
  return [
    ...start, { text: ` of ${order.symbol} at the market open` }, ...when,
    { text: ', within 5% of the current price.' }
  ];
}

function describeEntry(order: OrderDescription): SentencePart[] {
  const verb = order.side === 'BUY' ? 'buy' : 'sell';
  const shares = sharesText(order.quantity);
  const limit = order.limitPrice == null ? '$—' : formatMoney(order.limitPrice);
  const stop = order.stopPrice == null ? '$—' : formatMoney(order.stopPrice);
  const orBetter = order.side === 'BUY' ? 'or lower' : 'or higher';
  const moves = order.side === 'BUY' ? 'rises' : 'falls';
  const until = untilPart(order);

  switch (order.type) {
    case 'MARKET':
      return describeMarket(order, verb, shares);
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
    case 'TRAILING_STOP':
      return [
        { text: `If ${order.symbol} ${moves} ` }, { text: trailText(order.trailAmount, order.trailPercent), strong: true },
        { text: ` from its ${order.side === 'BUY' ? 'low' : 'high'}, ${verb} ` },
        { text: shares, strong: true }, { text: ' at the market price' }, until
      ];
  }
}

export function sentenceText(parts: SentencePart[]): string {
  return parts.map(p => p.text).join('');
}

/** The price an open order is waiting for: its stop until that's reached, then its limit. */
export function waitingPrice(order: Pick<OpenOrder, 'orderType' | 'limitPrice' | 'stopPrice' | 'triggeredAt'>): number | null {
  if (order.orderType === 'STOP' || order.orderType === 'TRAILING_STOP'
      || (order.orderType === 'STOP_LIMIT' && !order.triggeredAt)) {
    return order.stopPrice;
  }
  return order.limitPrice;
}

/**
 * Which way the price an open order fills at must move to reach the price it waits for: a buy
 * limit (or a triggered buy stop limit) and a sell stop wait for it to fall, the others to rise.
 */
export function requiredMove(order: Pick<OpenOrder, 'orderType' | 'side' | 'triggeredAt'>): 'rise' | 'fall' {
  const onStop = order.orderType === 'STOP' || order.orderType === 'TRAILING_STOP'
    || (order.orderType === 'STOP_LIMIT' && !order.triggeredAt);
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
  return `${distance.percent.toFixed(percentDigits(distance.percent))}% ${distance.direction === 'rise' ? 'above' : 'below'} ${reference}`;
}

/** Two decimals under 1%, one under 10%, none above. */
function percentDigits(percent: number): number {
  if (percent < 1) {
    return 2;
  }
  return percent < 10 ? 1 : 0;
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
  // A bracket's two exits sell the same shares, so a linked group holds them once, as its largest leg.
  const linked = new Map<string, number>();
  for (const order of orders) {
    const account = holds[order.accountId] ??= { cash: 0, shares: {} };
    if (order.side === 'BUY') {
      account.cash += order.reservedCash ?? 0;
    } else if (order.ocoGroupId) {
      linked.set(order.ocoGroupId, Math.max(linked.get(order.ocoGroupId) ?? 0, order.quantity));
    } else {
      account.shares[order.symbol] = (account.shares[order.symbol] ?? 0) + order.quantity;
    }
  }
  for (const group of new Set(orders.filter(o => o.side === 'SELL' && o.ocoGroupId).map(o => o.ocoGroupId!))) {
    const leg = orders.find(o => o.ocoGroupId === group)!;
    const account = holds[leg.accountId];
    account.shares[leg.symbol] = (account.shares[leg.symbol] ?? 0) + linked.get(group)!;
  }
  return holds;
}

/** Whether an order is a market order waiting OPEN for the market to open. */
export function isQueuedOrder(order: { status: string; orderType?: OrderType | null }): boolean {
  return order.status === 'OPEN' && (order.orderType ?? 'MARKET') === 'MARKET';
}

/** "Limit buy", "Stop sell", "Market buy" (a market order queued for the open)... */
export function openOrderTitle(order: Pick<OpenOrder, 'orderType' | 'side'>): string {
  return `${orderTypeLabel(order.orderType)} ${order.side === 'BUY' ? 'buy' : 'sell'}`;
}

function capitalise(text: string): string {
  return text.charAt(0).toUpperCase() + text.slice(1);
}
