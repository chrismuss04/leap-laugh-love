/** How Raise Cash picks what to sell. */
export type SellStrategy = 'even' | 'winners' | 'losers';

export interface SellableHolding {
  symbol: string;
  /** Shares held. */
  quantity: number;
  /** Shares already promised to open sell orders, so not free to sell again. */
  onHold?: number;
  /** The price a sell is expected to fill at (the bid when known), or null when there's no price yet. */
  price: number | null;
  /** Average cost per share, or null when unknown. */
  averageCost: number | null;
}

export interface SellInput {
  holdings: SellableHolding[];
  /** Buying power: cash less what open orders already hold. */
  cash: number;
  /** The cash the client wants to end up with. */
  target: number;
  strategy: SellStrategy;
  /** Symbols the client has locked with 'Don't sell'. */
  locked?: ReadonlySet<string>;
}

export interface PlannedSell {
  symbol: string;
  quantity: number;
  price: number;
  /** quantity x price, in dollars rounded to cents. */
  proceeds: number;
  /** The unrealised gain (or, negative, loss) the sell realises against average cost; null when that's unknown. */
  realised: number | null;
}

export interface SellPlan {
  /** In the order the strategy picked them. Empty when the target needs nothing sold. */
  sells: PlannedSell[];
  /** The target actually planned for: the one asked for, kept between cash and maxCash. */
  target: number;
  /** True when the target asked for was more than the unlocked holdings can raise. */
  capped: boolean;
  /** What the sells raise. Whole shares only, so this can be a little over what the target needs. */
  raised: number;
  /** Cash once every sell fills at its price. */
  cashAfter: number;
  /** The gain or loss the sells realise between them, from those with a known average cost; null when none has one. */
  realised: number | null;
  /** The most cash reachable: cash plus every unlocked holding's free shares. */
  maxCash: number;
  /** The most cash reachable with nothing locked, for the top of the bar. */
  fullCash: number;
  /** Locked symbols that hold shares free to sell. */
  locked: string[];
  /** Symbols with shares to sell but no price yet, so left out. */
  unpriced: string[];
}

/** Whole cents, so totals never drift. */
const toCents = (dollars: number) => Math.round(dollars * 100);
const toDollars = (cents: number) => cents / 100;

interface Candidate {
  symbol: string;
  free: number;
  priceCents: number;
  /** Average cost per share in cents, or null when unknown. */
  costCents: number | null;
  /** Unrealised gain as a fraction of average cost, or null without a usable average cost. */
  gain: number | null;
}

/**
 * Raise Cash: the sells that take the account's cash up to a target. Only free shares of unlocked,
 * priced holdings are sold, in whole shares, so the cash raised can be a little over the target.
 *
 * - even: trims every holding by the same fraction, rounding down, then adds a share at a time to
 *   the holdings that lost the most to rounding until the target is met.
 * - winners: sells the largest unrealised gain % first, whole positions, then part of the last.
 * - losers: the same from the largest unrealised loss %. The strategy only sets the order, so both
 *   carry on into the rest of the holdings once the winners or losers run out.
 *
 * Holdings without an average cost go last for winners and losers; ties go by symbol. Cheap enough
 * to run on every step of a drag.
 */
export function planSells(input: SellInput): SellPlan {
  const lockedSet = input.locked ?? new Set<string>();
  const cashCents = toCents(input.cash);
  const unpriced: string[] = [];
  const locked: string[] = [];
  const candidates: Candidate[] = [];
  let fullCents = cashCents;

  for (const h of input.holdings) {
    const free = Math.max(0, Math.floor(h.quantity) - Math.ceil(h.onHold ?? 0));
    if (free === 0) {
      continue;
    }
    if (h.price === null || !(h.price > 0)) {
      unpriced.push(h.symbol);
      continue;
    }
    const priceCents = toCents(h.price);
    fullCents += free * priceCents;
    if (lockedSet.has(h.symbol)) {
      locked.push(h.symbol);
      continue;
    }
    const known = h.averageCost !== null && h.averageCost > 0;
    const gain = known ? (h.price - h.averageCost!) / h.averageCost! : null;
    candidates.push({ symbol: h.symbol, free, priceCents, costCents: known ? toCents(h.averageCost!) : null, gain });
  }

  const maxCents = cashCents + candidates.reduce((sum, c) => sum + c.free * c.priceCents, 0);
  const askedCents = toCents(input.target);
  const targetCents = Math.min(Math.max(askedCents, cashCents), maxCents);
  const needCents = targetCents - cashCents;

  const quantities = needCents <= 0 ? new Map<string, number>()
    : input.strategy === 'even' ? sellEvenly(candidates, needCents, maxCents - cashCents)
    : sellInOrder(rank(candidates, input.strategy), needCents);

  const order = input.strategy === 'even' ? bySymbol(candidates) : rank(candidates, input.strategy);
  const sells = order
    .filter(c => (quantities.get(c.symbol) ?? 0) > 0)
    .map(c => {
      const quantity = quantities.get(c.symbol)!;
      return {
        symbol: c.symbol, quantity, price: toDollars(c.priceCents), proceeds: toDollars(quantity * c.priceCents),
        realised: c.costCents === null ? null : toDollars(quantity * (c.priceCents - c.costCents))
      };
    });
  const raisedCents = sells.reduce((sum, s) => sum + toCents(s.proceeds), 0);
  const known = sells.filter(s => s.realised !== null);
  const realised = known.length ? toDollars(known.reduce((sum, s) => sum + toCents(s.realised!), 0)) : null;

  return {
    sells,
    target: toDollars(targetCents),
    capped: askedCents > maxCents,
    raised: toDollars(raisedCents),
    cashAfter: toDollars(cashCents + raisedCents),
    realised,
    maxCash: toDollars(maxCents),
    fullCash: toDollars(fullCents),
    locked: locked.sort(),
    unpriced: unpriced.sort()
  };
}

function bySymbol(candidates: Candidate[]): Candidate[] {
  return [...candidates].sort((a, b) => a.symbol.localeCompare(b.symbol));
}

/** Winners: largest gain first. Losers: largest loss first. Unknown gains last, ties by symbol. */
function rank(candidates: Candidate[], strategy: 'winners' | 'losers'): Candidate[] {
  const direction = strategy === 'winners' ? -1 : 1;
  return [...candidates].sort((a, b) => {
    if (a.gain === null || b.gain === null) {
      return a.gain === b.gain ? a.symbol.localeCompare(b.symbol) : a.gain === null ? 1 : -1;
    }
    return (a.gain - b.gain) * direction || a.symbol.localeCompare(b.symbol);
  });
}

/** Whole positions in order, then just enough of the last one. */
function sellInOrder(ranked: Candidate[], needCents: number): Map<string, number> {
  const quantities = new Map<string, number>();
  let remaining = needCents;
  for (const c of ranked) {
    if (remaining <= 0) {
      break;
    }
    const quantity = Math.min(c.free, Math.ceil(remaining / c.priceCents));
    quantities.set(c.symbol, quantity);
    remaining -= quantity * c.priceCents;
  }
  return quantities;
}

/** The same fraction of every holding, rounded down, then a share at a time by largest remainder. */
function sellEvenly(candidates: Candidate[], needCents: number, availableCents: number): Map<string, number> {
  const fraction = needCents / availableCents;
  const quantities = new Map<string, number>();
  let raised = 0;
  const leftovers = candidates.map(c => {
    const exact = c.free * fraction;
    const quantity = Math.floor(exact);
    quantities.set(c.symbol, quantity);
    raised += quantity * c.priceCents;
    return { candidate: c, remainder: exact - quantity };
  }).sort((a, b) => b.remainder - a.remainder || a.candidate.symbol.localeCompare(b.candidate.symbol));

  // Rounding every holding up would cover the need, so at most one extra share each is enough.
  for (const { candidate } of leftovers) {
    if (raised >= needCents) {
      break;
    }
    const quantity = quantities.get(candidate.symbol)!;
    if (quantity < candidate.free) {
      quantities.set(candidate.symbol, quantity + 1);
      raised += candidate.priceCents;
    }
  }
  return quantities;
}
