import { OrderSide, OrderType, TimeInForce } from '../services/order';
import { OrderTemplate, OrderTemplateRequest } from '../services/order-templates';
import { toCents } from './order-types';

/** The ticket as it stands, in the prices the client entered. */
export interface TicketSetup {
  side: OrderSide;
  orderType: OrderType;
  timeInForce: TimeInForce;
  /** The price this side fills at: the ask for a buy, the bid for a sell. */
  sidePrice: number;
  limitPrice: number | null;
  stopPrice: number | null;
  /** A trailing stop's trail, in percent of the price or in dollars. */
  trailUnit: 'PERCENT' | 'AMOUNT';
  trailValue: number | null;
  /** The price a buy enters at (the market's, or the order's own limit or stop). */
  entryPrice: number | null;
  /** The bracket's exits, when it is on. */
  takeProfit: number | null;
  stopLoss: number | null;
}

/** The prices a template works out to against today's market. */
export interface AppliedTemplate {
  side: OrderSide;
  orderType: OrderType;
  timeInForce: TimeInForce;
  limitPrice: number | null;
  stopPrice: number | null;
  trailPercent: number | null;
  /** Percents for the bracket; prices follow once the ticket knows its entry price. */
  takeProfitPercent: number | null;
  stopLossPercent: number | null;
}

function round2(value: number): number {
  return Math.round(value * 100) / 100;
}

/** A price as signed percent from a reference price, to two places. */
function offsetPercent(price: number, reference: number): number {
  return round2((price / reference - 1) * 100);
}

/** A reference price moved by a signed percent, to the cent. */
function priceAt(reference: number, percent: number): number {
  return toCents(reference * (1 + percent / 100));
}

/**
 * Turns the ticket into a template: prices become percent offsets from the market so it can be
 * applied on any day. Null when a price the type needs hasn't been entered.
 */
export function templateFromTicket(name: string, ticket: TicketSetup): OrderTemplateRequest | null {
  const { orderType, sidePrice } = ticket;
  if (!(sidePrice > 0)) {
    return null;
  }
  const hasLimit = orderType === 'LIMIT' || orderType === 'STOP_LIMIT';
  const hasStop = orderType === 'STOP' || orderType === 'STOP_LIMIT';
  if ((hasLimit && ticket.limitPrice == null) || (hasStop && ticket.stopPrice == null)) {
    return null;
  }
  let trailPercent: number | null = null;
  if (orderType === 'TRAILING_STOP') {
    if (ticket.trailValue == null) {
      return null;
    }
    trailPercent = ticket.trailUnit === 'PERCENT' ? round2(ticket.trailValue) : round2(ticket.trailValue / sidePrice * 100);
  }
  let takeProfitPercent: number | null = null;
  let stopLossPercent: number | null = null;
  if (ticket.side === 'BUY' && ticket.takeProfit != null && ticket.stopLoss != null) {
    if (!(ticket.entryPrice != null && ticket.entryPrice > 0)) {
      return null;
    }
    takeProfitPercent = offsetPercent(ticket.takeProfit, ticket.entryPrice);
    stopLossPercent = round2((1 - ticket.stopLoss / ticket.entryPrice) * 100);
  }
  return {
    name: name.trim(),
    side: ticket.side,
    orderType,
    timeInForce: orderType === 'MARKET' ? 'DAY' : ticket.timeInForce,
    limitOffsetPercent: hasLimit ? offsetPercent(ticket.limitPrice!, sidePrice) : null,
    stopOffsetPercent: hasStop ? offsetPercent(ticket.stopPrice!, sidePrice) : null,
    trailPercent,
    takeProfitPercent,
    stopLossPercent
  };
}

/** The prices a template sets on the ticket, worked out from the price this side fills at now. */
export function applyTemplate(template: OrderTemplate, sidePrice: number): AppliedTemplate {
  return {
    side: template.side,
    orderType: template.orderType,
    timeInForce: template.timeInForce,
    limitPrice: template.limitOffsetPercent != null ? priceAt(sidePrice, template.limitOffsetPercent) : null,
    stopPrice: template.stopOffsetPercent != null ? priceAt(sidePrice, template.stopOffsetPercent) : null,
    trailPercent: template.trailPercent,
    takeProfitPercent: template.takeProfitPercent,
    stopLossPercent: template.stopLossPercent
  };
}

/** A bracket's exit prices from its percents and the entry price. */
export function bracketPrices(entry: number, takeProfitPercent: number, stopLossPercent: number):
    { takeProfit: number; stopLoss: number } {
  return { takeProfit: priceAt(entry, takeProfitPercent), stopLoss: priceAt(entry, -stopLossPercent) };
}

function signed(percent: number): string {
  return `${percent < 0 ? '−' : '+'}${Math.abs(+percent.toFixed(2))}%`;
}

/** A name to start from, e.g. "Buy, stop −8%, take-profit +20%, until cancelled". */
export function suggestedTemplateName(template: OrderTemplateRequest): string {
  const parts = [template.side === 'BUY' ? 'Buy' : 'Sell'];
  const type = { MARKET: '', LIMIT: 'limit', STOP: 'stop', STOP_LIMIT: 'stop limit', TRAILING_STOP: 'trailing stop' }[template.orderType];
  if (template.orderType === 'LIMIT' && template.limitOffsetPercent != null) {
    parts.push(`${type} ${signed(template.limitOffsetPercent)}`);
  } else if ((template.orderType === 'STOP' || template.orderType === 'STOP_LIMIT') && template.stopOffsetPercent != null) {
    parts.push(`${type} ${signed(template.stopOffsetPercent)}`);
  } else if (template.orderType === 'TRAILING_STOP' && template.trailPercent != null) {
    parts.push(`${type} ${+template.trailPercent.toFixed(2)}%`);
  } else {
    // Market orders and templates without an offset add no price description.
  }
  if (template.stopLossPercent != null) {
    parts.push(`stop-loss ${signed(-template.stopLossPercent)}`);
  }
  if (template.takeProfitPercent != null) {
    parts.push(`take-profit ${signed(template.takeProfitPercent)}`);
  }
  if (template.orderType !== 'MARKET') {
    parts.push(template.timeInForce === 'GTC' ? 'until cancelled' : 'today');
  }
  return parts.join(', ').slice(0, 40);
}
