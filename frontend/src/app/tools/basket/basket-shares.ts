import { DEFAULT_CASH_BUFFER, PlannedOrder } from '../../shared/order-plan';

/**
 * Basket Builder: what each stock's amount buys in whole shares, at the price a market buy fills
 * at (the ask). Money is in cents throughout, so totals add up exactly.
 */

/** Absorbs floating-point error, so $1,500 at $150 counts as 10 shares rather than 9.999... */
const EPSILON = 1e-9;

/** One stock's buy. */
export interface BasketBuy {
  symbol: string;
  /** What the split gives this stock. */
  amountCents: number;
  /** The ask in dollars, or null until there's a price. */
  price: number | null;
  shares: number;
  costCents: number;
  /** What the amount has over the shares' cost. */
  leftoverCents: number;
  /** Has a price, but its amount doesn't reach one share. */
  notEnough: boolean;
}

export interface BasketRounding {
  buys: BasketBuy[];
  investedCents: number;
  /** The chosen total less what the shares cost. */
  leftoverCents: number;
}

const priceCents = (price: number) => Math.round(price * 100);

function buy(symbol: string, amountCents: number, price: number | null, shares: number): BasketBuy {
  const costCents = price === null ? 0 : shares * priceCents(price);
  return {
    symbol, amountCents, price, shares, costCents,
    leftoverCents: amountCents - costCents,
    notEnough: price !== null && shares === 0
  };
}

function totals(buys: BasketBuy[], chosenCents: number): BasketRounding {
  const investedCents = buys.reduce((sum, b) => sum + b.costCents, 0);
  return { buys, investedCents, leftoverCents: chosenCents - investedCents };
}

/** Each stock's whole shares for its amount, rounding down, with what's left over. */
export function roundShares(symbols: string[], amountsCents: number[], prices: Record<string, number | null>): BasketRounding {
  const buys = symbols.map((symbol, i) => {
    const price = prices[symbol] ?? null;
    const usable = price !== null && price > 0 ? price : null;
    const shares = usable === null ? 0 : Math.floor(amountsCents[i] / priceCents(usable) + EPSILON);
    return buy(symbol, amountsCents[i], usable, shares);
  });
  return totals(buys, amountsCents.reduce((sum, a) => sum + a, 0));
}

/**
 * Spends the leftover on extra shares: a share at a time, cheapest stock first, round after round
 * until no share fits. Only ever the leftover of the amount the client chose - never more of their
 * cash - so the basket never costs more than `chosenCents`.
 */
export function useLeftovers(rounding: BasketRounding, chosenCents: number): { rounding: BasketRounding; extras: Record<string, number> } {
  const extras: Record<string, number> = {};
  const shares = new Map(rounding.buys.map(b => [b.symbol, b.shares]));
  let pool = chosenCents - rounding.investedCents;
  const cheapestFirst = rounding.buys.filter(b => b.price !== null)
    .sort((a, b) => priceCents(a.price!) - priceCents(b.price!) || (a.symbol < b.symbol ? -1 : 1));
  let added = true;
  while (added) {
    added = false;
    for (const b of cheapestFirst) {
      const cost = priceCents(b.price!);
      if (cost <= pool) {
        pool -= cost;
        shares.set(b.symbol, shares.get(b.symbol)! + 1);
        extras[b.symbol] = (extras[b.symbol] ?? 0) + 1;
        added = true;
      }
    }
  }
  const buys = rounding.buys.map(b => buy(b.symbol, b.amountCents, b.price, shares.get(b.symbol)!));
  const result = totals(buys, chosenCents);
  if (result.investedCents > chosenCents) {
    throw new Error('Leftovers spent more than the chosen amount');
  }
  return { rounding: result, extras };
}

/**
 * The most the basket can cost, in cents: the account's cash plus what the plan's other sells
 * raise, less the cash buffer the order engine keeps on every buy, less the plan's other buys.
 * The same check the tray makes, so a basket at the maximum doesn't trip "not enough cash".
 */
export function basketMaxCents(cash: number, otherOrders: Pick<PlannedOrder, 'side' | 'estimatedValue'>[],
    cashBuffer = DEFAULT_CASH_BUFFER): number {
  const total = (side: 'BUY' | 'SELL') =>
    otherOrders.filter(o => o.side === side).reduce((sum, o) => sum + o.estimatedValue, 0);
  const max = (cash + total('SELL')) / (1 + cashBuffer) - total('BUY');
  return Math.max(0, Math.floor(max * 100 + EPSILON));
}
