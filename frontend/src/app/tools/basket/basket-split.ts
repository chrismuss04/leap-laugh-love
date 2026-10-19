import { apportion, dragBoundary, moveBoundary, setTarget } from '../rebalance/targets';

/**
 * Basket Builder: how the client's amount is split across the basket, one amount per stock, in
 * cents and in basket order. The amounts always add up to the total exactly, so a typed $250.00
 * stays $250.00. Built on the Rebalance Donut's target maths, which works in any whole unit.
 */

/** Nothing in the basket is locked; the donut's helpers take a lock per slice. */
const unlocked = (count: number) => new Array<boolean>(count).fill(false);

/** The total split evenly, the odd cents going to the first stocks. */
export function evenSplit(totalCents: number, count: number): number[] {
  return apportion(new Array<number>(count).fill(1), totalCents);
}

/** A divider moves in steps of 1% of the total, and at least a cent. */
export function dividerStep(totalCents: number): number {
  return Math.max(1, Math.round(totalCents / 100));
}

/** The split for a new total, keeping each stock's share; an empty split starts even. */
export function rescale(amounts: number[], totalCents: number): number[] {
  return amounts.some(a => a > 0) ? apportion(amounts, totalCents) : evenSplit(totalCents, amounts.length);
}

/**
 * Moves the divider after stock `divider` by `deltaCents`: positive gives the stock before it more,
 * taken from the one after. Stops when either reaches $0.
 */
export function moveDivider(amounts: number[], divider: number, deltaCents: number): number[] {
  return moveBoundary(amounts, unlocked(amounts.length), divider, deltaCents);
}

/** Drags the divider towards `positionCents` from the bar's start, snapped to `stepCents`. */
export function dragDivider(amounts: number[], divider: number, positionCents: number, stepCents: number): number[] {
  return dragBoundary(amounts, unlocked(amounts.length), divider, positionCents, stepCents);
}

/**
 * Sets one stock's amount (clamped to the total) and spreads the difference over the others in
 * proportion, so the total stays the same.
 */
export function setAmount(amounts: number[], index: number, cents: number): number[] {
  const total = amounts.reduce((sum, a) => sum + a, 0);
  return setTarget(amounts, unlocked(amounts.length), index, cents, total);
}

/** "$1,234.56" as cents, or null when it isn't an amount. */
export function parseCents(text: string): number | null {
  const cleaned = text.replace(/[$,\s]/g, '');
  if (!/^\d*(\.\d{0,2})?$/.test(cleaned) || cleaned === '' || cleaned === '.') {
    return null;
  }
  return Math.round(Number(cleaned) * 100);
}
