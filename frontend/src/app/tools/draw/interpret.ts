import { OrderLine, PlanOrderType } from '../../shared/order-plan';
import { DataPoint } from './stroke';

/**
 * Draw Your Trade: reading a trade off a drawn line.
 *
 * Plain TypeScript with no Angular dependencies. The line starts at now (step 0) at the current
 * price; what it does after that says what to trade:
 *
 * - rally: it ends higher without dipping first - buy now, take profit at its peak.
 * - dip-then-rally: it dips, then ends higher - buy with a limit at the bottom of the dip.
 * - rally-then-fall: it rises, then ends lower - sell what's held with a limit at the peak.
 * - fall: it ends lower without rising first - sell what's held now.
 * - flat: it never leaves the noise band - nothing to trade.
 *
 * A buy carries a bracket: profit at the peak drawn after the entry, a stop loss under the lowest
 * point drawn after it (or, for a line that never dips, half the profit's distance below).
 */

export type DrawShape = 'rally' | 'dip-then-rally' | 'rally-then-fall' | 'fall' | 'flat';

export interface TradeIdea {
  shape: DrawShape;
  side: 'BUY' | 'SELL';
  /** MARKET to trade now, LIMIT to wait for the price the line reaches. */
  entryType: Extract<PlanOrderType, 'MARKET' | 'LIMIT'>;
  /** The price the trade is expected at: the current price for a market order, else the limit. */
  entry: number;
  /** Where the entry was drawn, in steps from now. */
  entryStep: number;
  /** A buy's take-profit price; for a sell, where the line ends. */
  target: number;
  targetStep: number;
  /** A buy's stop-loss price; null for a sell. */
  stop: number | null;
  /** A sell of a stock the account doesn't hold, which the client can't place. */
  needsShares: boolean;
}

export interface InterpretOptions {
  /**
   * Moves smaller than this fraction of the current price are noise: a line inside the band is
   * flat, and dips and peaks shallower than it are ignored. Defaults to 0.5%.
   */
  band?: number;
  /** Shares of the symbol the account holds. */
  held?: number;
}

export const DEFAULT_BAND = 0.005;

const cents = (value: number) => Math.round(value * 100) / 100;

const lowest = (points: readonly DataPoint[]) =>
  points.reduce((low, p) => (p.price < low.price ? p : low), points[0]);
const highest = (points: readonly DataPoint[]) =>
  points.reduce((high, p) => (p.price > high.price ? p : high), points[0]);

/**
 * The trade a line suggests, or a 'flat' idea for one that goes nowhere. The line runs forward from
 * now; its first point is taken as the current price when `price` isn't given.
 */
export function interpret(line: readonly DataPoint[], price = line[0]?.price, options: InterpretOptions = {}): TradeIdea | null {
  const future = line.filter(p => p.step >= 0 && Number.isFinite(p.price) && p.price > 0);
  if (future.length < 2 || !(price > 0)) {
    return null;
  }
  const band = price * (options.band ?? DEFAULT_BAND);
  const held = options.held ?? 0;
  const end = future[future.length - 1];
  const low = lowest(future);
  const high = highest(future);

  const flat = Math.abs(end.price - price) < band && high.price - low.price < 2 * band;
  if (flat) {
    return {
      shape: 'flat', side: 'BUY', entryType: 'MARKET', entry: cents(price), entryStep: 0,
      target: cents(end.price), targetStep: end.step, stop: null, needsShares: false
    };
  }

  if (end.price > price) {
    // Only a dip before the line's peak is somewhere to buy.
    const beforePeak = future.filter(p => p.step <= high.step);
    const dip = lowest(beforePeak);
    const dips = dip.price < price - band;
    const entry = dips ? dip : { step: 0, price };
    const after = future.filter(p => p.step >= entry.step);
    const peak = highest(after);
    return buyIdea(dips ? 'dip-then-rally' : 'rally', dips ? 'LIMIT' : 'MARKET', entry, peak, after, band);
  }

  // The line ends lower: sell at its peak if it rises first, else now.
  const beforeLow = future.filter(p => p.step <= low.step);
  const peak = highest(beforeLow);
  const rises = peak.price > price + band;
  return {
    shape: rises ? 'rally-then-fall' : 'fall',
    side: 'SELL',
    entryType: rises ? 'LIMIT' : 'MARKET',
    entry: cents(rises ? peak.price : price),
    entryStep: rises ? peak.step : 0,
    target: cents(end.price),
    targetStep: end.step,
    stop: null,
    needsShares: held < 1
  };
}

function buyIdea(shape: DrawShape, entryType: 'MARKET' | 'LIMIT', entry: DataPoint, peak: DataPoint,
                 after: readonly DataPoint[], band: number): TradeIdea {
  const reward = peak.price - entry.price;
  // The lowest the line goes once bought; a dip below the entry is where the idea is wrong.
  const afterEntry = after.filter(p => p.step > entry.step);
  const trough = afterEntry.length ? lowest(afterEntry).price : entry.price;
  const stop = trough < entry.price - band ? trough - band : entry.price - Math.max(band, reward / 2);
  return {
    shape, side: 'BUY', entryType,
    entry: cents(entry.price), entryStep: entry.step,
    target: cents(peak.price), targetStep: peak.step,
    stop: cents(Math.max(stop, 0.01)),
    needsShares: false
  };
}

/** The idea with its levels moved - by dragging their handles - kept in a valid order. */
export function adjustIdea(idea: TradeIdea, change: Partial<Pick<TradeIdea, 'entry' | 'target' | 'stop'>>): TradeIdea {
  const next = { ...idea, ...change };
  next.entry = cents(next.entry);
  next.target = cents(next.target);
  if (next.side === 'BUY') {
    // A buy's take profit stays above its entry, and its stop loss below.
    next.target = Math.max(next.target, cents(next.entry + 0.01));
    next.stop = cents(Math.min(next.stop ?? next.entry - 0.01, next.entry - 0.01));
    next.stop = Math.max(next.stop, 0.01);
  }
  return next;
}

/** Whole shares a dollar amount buys at the entry; for a sell, capped at the shares held. */
export function sharesFor(idea: TradeIdea, dollars: number, held = Infinity): number {
  if (!(idea.entry > 0) || !(dollars > 0)) {
    return 0;
  }
  const shares = Math.floor(dollars / idea.entry + 1e-9);
  return idea.side === 'SELL' ? Math.min(shares, Math.floor(held)) : shares;
}

export interface Payoff {
  /** What the trade makes if the line is right, in dollars. */
  reward: number;
  /** What a buy loses at its stop loss, in dollars; 0 for a sell. */
  risk: number;
  /** reward / risk, or null without a risk. */
  ratio: number | null;
}

/**
 * What the trade makes or loses on `shares`. A buy makes the take profit less the entry and risks
 * the entry less the stop loss; a sell makes (saves) what the line falls after it.
 */
export function payoff(idea: TradeIdea, shares: number): Payoff {
  if (idea.side === 'BUY') {
    const reward = cents((idea.target - idea.entry) * shares);
    const risk = cents((idea.entry - (idea.stop ?? idea.entry)) * shares);
    return { reward, risk, ratio: risk > 0 ? reward / risk : null };
  }
  return { reward: cents(Math.max(idea.entry - idea.target, 0) * shares), risk: 0, ratio: null };
}

/**
 * The idea as an order for the trade plan, or null when there's nothing to place: a flat line, no
 * shares, or a sell of shares the account doesn't hold.
 */
export function toOrderLine(symbol: string, idea: TradeIdea, shares: number, timeInForce: 'DAY' | 'GTC'): OrderLine | null {
  if (idea.shape === 'flat' || idea.needsShares || !(shares >= 1)) {
    return null;
  }
  const line: OrderLine = { symbol, side: idea.side, quantity: Math.floor(shares) };
  if (idea.entryType === 'LIMIT') {
    line.orderType = 'LIMIT';
    line.limitPrice = idea.entry;
  }
  // A market order fills now, so only a waiting one takes a time in force. A bracket's exits are
  // always good until cancelled.
  if (timeInForce === 'GTC' && idea.entryType === 'LIMIT') {
    line.timeInForce = 'GTC';
  }
  if (idea.side === 'BUY' && idea.stop !== null) {
    line.bracket = { takeProfit: idea.target, stopLoss: idea.stop };
  }
  return line;
}
