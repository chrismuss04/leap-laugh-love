/**
 * Trading Tools: the one engine every tool uses to turn target holdings into market orders.
 *
 * Plain TypeScript with no Angular dependencies, so it can be unit tested on its own. The engine
 * only plans - submitting the orders, in the order given, is up to the caller.
 */

export type PlanSide = 'BUY' | 'SELL';

/** Shares of one symbol held in the account. */
export interface PlanPosition {
  symbol: string;
  quantity: number;
}

/**
 * What one symbol should end up as: a number of shares, a dollar value, or a percentage (0-100)
 * of the account's value. Values and percentages are measured at the last trade price, the same
 * way the rest of the app values positions.
 */
export type PlanTarget =
  | { symbol: string; quantity: number }
  | { symbol: string; value: number }
  | { symbol: string; percent: number };

/** How far a symbol's ask and bid sit from its last trade, as the dashboard's quote offsets do. */
export interface PlanQuoteOffset {
  ask: number;
  bid: number;
}

export interface PlanInput {
  positions: PlanPosition[];
  /** Last trade price per symbol, e.g. the live price stream merged over a REST snapshot. */
  prices: Record<string, number>;
  /**
   * Ask and bid offsets per symbol. Market orders fill at the ask (buy) or bid (sell), so orders
   * are priced at those when known, and at the last trade otherwise.
   */
  quoteOffsets?: Record<string, PlanQuoteOffset>;
  /** Cash available to spend. */
  cash: number;
  /** Targets; when a symbol appears more than once the last target wins. */
  targets: PlanTarget[];
  /** Held symbols without a target are left alone ('keep', the default) or sold off ('sell'). */
  untargeted?: 'keep' | 'sell';
  /** Orders worth less than this many dollars are dropped. Defaults to $1. */
  minOrderValue?: number;
  /**
   * Headroom kept on buys, as a fraction of their cost, for prices moving between the plan and the
   * fill. Defaults to 0.5%.
   */
  cashBuffer?: number;
}

export interface PlannedOrder {
  symbol: string;
  side: PlanSide;
  /** Whole shares, at least 1. */
  quantity: number;
  /** The per-share price the order was planned at; send it as the order's quotedPrice. */
  quotedPrice: number;
  /** quantity x quotedPrice, in dollars rounded to cents. */
  estimatedValue: number;
}

/** One symbol's place in the account after the plan, valued at its last trade price. */
export interface PlannedHolding {
  symbol: string;
  quantity: number;
  value: number;
  /** Fraction (0-1) of the account's value after the plan. */
  share: number;
  /** The fraction (0-1) the targets asked for, or null for a symbol without a target. */
  targetShare: number | null;
}

export type PlanWarningCode = 'not-enough-cash' | 'too-small' | 'no-price' | 'targets-over-100' | 'invalid-target';

export interface PlanWarning {
  code: PlanWarningCode;
  /** The symbol the warning is about, or null for the plan as a whole. */
  symbol: string | null;
  message: string;
}

export interface OrderPlan {
  /** Every SELL, then every BUY, each by symbol - sells first so their proceeds fund the buys. */
  orders: PlannedOrder[];
  /** Cash after every order fills at its quoted price. */
  cashAfter: number;
  /** Holdings after the plan, by symbol; symbols left with no shares and no target are omitted. */
  allocationAfter: PlannedHolding[];
  /** Fraction (0-1) of the account's value left in cash after the plan. */
  cashShareAfter: number;
  warnings: PlanWarning[];
}

export const DEFAULT_MIN_ORDER_VALUE = 1;
export const DEFAULT_CASH_BUFFER = 0.005;

/** Absorbs floating-point error, so 1500 / 150 counts as 10 shares rather than 9.999... */
const EPSILON = 1e-9;

const cents = (value: number) => Math.round(value * 100) / 100;
const wholeShares = (value: number) => Math.floor(value + EPSILON);
const bySymbol = <T extends { symbol: string }>(a: T, b: T) => (a.symbol < b.symbol ? -1 : a.symbol > b.symbol ? 1 : 0);

interface Draft {
  symbol: string;
  quantity: number;
  price: number;
}

/**
 * Plans the market orders that move an account as close to its targets as whole shares and its
 * cash allow. Quantities round toward the target and never past it; a sell never exceeds the
 * shares held. If the buys cost more than cash plus sale proceeds, every buy is scaled down by
 * the same factor. The result depends only on the input.
 */
export function planOrders(input: PlanInput): OrderPlan {
  const minOrderValue = input.minOrderValue ?? DEFAULT_MIN_ORDER_VALUE;
  const cashBuffer = Math.max(0, input.cashBuffer ?? DEFAULT_CASH_BUFFER);
  const warnings: PlanWarning[] = [];
  const warn = (code: PlanWarningCode, symbol: string | null, message: string) => warnings.push({ code, symbol, message });

  const held = new Map<string, number>();
  for (const position of input.positions) {
    if (position.quantity > 0) {
      held.set(position.symbol, (held.get(position.symbol) ?? 0) + position.quantity);
    }
  }
  const lastPrice = (symbol: string): number | null => {
    const price = input.prices[symbol];
    return Number.isFinite(price) && price > 0 ? price : null;
  };

  // The account's value now, which percentage targets are a share of.
  let totalValue = input.cash;
  for (const [symbol, quantity] of held) {
    totalValue += quantity * (lastPrice(symbol) ?? 0);
  }

  const targets = new Map<string, PlanTarget>();
  for (const target of input.targets) {
    targets.set(target.symbol, target);
  }

  // Percentages past 100% can't all be met; scale them back to fit.
  const percentTotal = [...targets.values()].reduce((sum, t) => sum + ('percent' in t ? Math.max(t.percent, 0) : 0), 0);
  const percentScale = percentTotal > 100 + EPSILON ? 100 / percentTotal : 1;
  if (percentScale < 1) {
    warn('targets-over-100', null, `Targets add up to ${cents(percentTotal)}%, so each was scaled back to fit 100%.`);
  }

  // Shares each symbol should end with: its target, or for an untargeted holding, what it has now
  // (or nothing, when untargeted holdings are sold).
  const targetShares = new Map<string, number>();
  for (const [symbol, target] of targets) {
    const amount = 'quantity' in target ? target.quantity : 'value' in target ? target.value : target.percent;
    if (!Number.isFinite(amount) || amount < 0) {
      warn('invalid-target', symbol, `${symbol}'s target isn't a positive amount, so it was treated as zero.`);
    }
    const wanted = Number.isFinite(amount) ? Math.max(amount, 0) : 0;
    if ('quantity' in target) {
      targetShares.set(symbol, wanted);
      continue;
    }
    const price = lastPrice(symbol);
    if (price === null) {
      warn('no-price', symbol, `There's no price for ${symbol} yet, so it was left out of the plan.`);
      continue;
    }
    const dollars = 'value' in target ? wanted : (totalValue * wanted * percentScale) / 100;
    targetShares.set(symbol, dollars / price);
  }
  if (input.untargeted === 'sell') {
    for (const symbol of held.keys()) {
      if (!targets.has(symbol)) {
        targetShares.set(symbol, 0);
      }
    }
  }

  // Whole-share trades toward each target, never past it.
  const sells: Draft[] = [];
  let buys: Draft[] = [];
  for (const [symbol, shares] of targetShares) {
    const current = held.get(symbol) ?? 0;
    const delta = shares - current;
    if (Math.abs(delta) < EPSILON) {
      continue;
    }
    const price = lastPrice(symbol);
    if (price === null) {
      warn('no-price', symbol, `There's no price for ${symbol} yet, so it was left out of the plan.`);
      continue;
    }
    const offset = input.quoteOffsets?.[symbol];
    if (delta < 0) {
      sells.push({ symbol, quantity: Math.min(wholeShares(-delta), current), price: price + (offset?.bid ?? 0) });
    } else {
      buys.push({ symbol, quantity: wholeShares(delta), price: price + (offset?.ask ?? 0) });
    }
  }

  const keptSells = dropTooSmall(sells, minOrderValue, warn);
  const proceeds = keptSells.reduce((sum, o) => sum + o.quantity * o.price, 0);
  const available = Math.max(input.cash + proceeds, 0);

  buys = dropTooSmall(buys, minOrderValue, warn);
  const cost = (orders: Draft[]) => orders.reduce((sum, o) => sum + o.quantity * o.price, 0) * (1 + cashBuffer);
  if (cost(buys) > available + EPSILON) {
    warn('not-enough-cash', null, 'There isn’t enough cash for every buy, so each was scaled down by the same share.');
    buys = dropTooSmall(scaleToFit(buys, available, cashBuffer), minOrderValue, warn);
  }

  const orders: PlannedOrder[] = [
    ...keptSells.sort(bySymbol).map(o => toOrder(o, 'SELL')),
    ...buys.sort(bySymbol).map(o => toOrder(o, 'BUY'))
  ];

  // Where the account ends up if every order fills at its quoted price.
  const cashAfter = cents(input.cash + proceeds - buys.reduce((sum, o) => sum + o.quantity * o.price, 0));
  const sharesAfter = new Map(held);
  for (const order of orders) {
    sharesAfter.set(order.symbol, (sharesAfter.get(order.symbol) ?? 0) + (order.side === 'BUY' ? order.quantity : -order.quantity));
  }
  const symbolsAfter = new Set([...sharesAfter.keys(), ...targets.keys()]);
  const valued = [...symbolsAfter]
    .map(symbol => {
      const quantity = sharesAfter.get(symbol) ?? 0;
      return { symbol, quantity, value: quantity * (lastPrice(symbol) ?? 0) };
    })
    .filter(h => h.quantity > 0 || targets.has(h.symbol));
  const totalAfter = cashAfter + valued.reduce((sum, h) => sum + h.value, 0);
  const share = (value: number) => (totalAfter > 0 ? value / totalAfter : 0);
  const allocationAfter = valued.sort(bySymbol).map(h => ({
    symbol: h.symbol,
    quantity: h.quantity,
    value: cents(h.value),
    share: share(h.value),
    targetShare: targetShareOf(targets.get(h.symbol), lastPrice(h.symbol), totalValue, percentScale)
  }));

  return { orders, cashAfter, allocationAfter, cashShareAfter: share(cashAfter), warnings };
}

/** Drops orders of no shares silently, and orders worth less than the minimum with a warning. */
function dropTooSmall(orders: Draft[], minOrderValue: number,
                      warn: (code: PlanWarningCode, symbol: string | null, message: string) => void): Draft[] {
  return orders.filter(order => {
    if (order.quantity < 1) {
      return false;
    }
    if (order.quantity * order.price < minOrderValue - EPSILON) {
      warn('too-small', order.symbol, `${order.symbol} would trade under $${minOrderValue}, which is too small to trade.`);
      return false;
    }
    return true;
  });
}

/**
 * Scales every buy by the one factor that fits the cash, in whole shares. Flooring leaves some
 * cash over; it goes a share at a time to the buys that lost the most to rounding, then by symbol,
 * while it still fits.
 */
function scaleToFit(buys: Draft[], available: number, cashBuffer: number): Draft[] {
  const fullCost = buys.reduce((sum, o) => sum + o.quantity * o.price, 0) * (1 + cashBuffer);
  const factor = fullCost > 0 ? available / fullCost : 0;
  const scaled = buys.map(o => {
    const exact = o.quantity * factor;
    return { draft: { ...o, quantity: wholeShares(exact) }, remainder: exact - wholeShares(exact) };
  });
  let spent = scaled.reduce((sum, s) => sum + s.draft.quantity * s.draft.price, 0) * (1 + cashBuffer);
  const byRemainder = [...scaled].sort((a, b) => b.remainder - a.remainder || bySymbol(a.draft, b.draft));
  for (const entry of byRemainder) {
    const extra = entry.draft.price * (1 + cashBuffer);
    if (entry.remainder > EPSILON && spent + extra <= available + EPSILON) {
      entry.draft.quantity += 1;
      spent += extra;
    }
  }
  return scaled.map(s => s.draft);
}

function toOrder(draft: Draft, side: PlanSide): PlannedOrder {
  return {
    symbol: draft.symbol,
    side,
    quantity: draft.quantity,
    quotedPrice: draft.price,
    estimatedValue: cents(draft.quantity * draft.price)
  };
}

function targetShareOf(target: PlanTarget | undefined, price: number | null, totalValue: number,
                       percentScale: number): number | null {
  if (!target || totalValue <= 0) {
    return null;
  }
  if ('percent' in target) {
    return (Math.max(target.percent, 0) * percentScale) / 100;
  }
  if (price === null) {
    return null;
  }
  const dollars = 'value' in target ? target.value : target.quantity * price;
  return Math.max(dollars, 0) / totalValue;
}
