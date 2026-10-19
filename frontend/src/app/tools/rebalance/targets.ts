/**
 * Rebalance Donut: target allocation maths. Targets are whole tenths of a percent (1000 = 100%), so
 * every operation can keep them summing to exactly 100% with integer arithmetic. Each function
 * takes the targets in ring order (clockwise from 12 o'clock) and returns new ones, or the same
 * array when nothing could change.
 *
 * Boundary i sits between slice i and slice i + 1; moving it clockwise grows the slice before it
 * and shrinks the one after. The seam at 12 o'clock (between the last slice and the first) stays
 * put, so the ring always starts in the same place. Locked slices keep their target: a boundary
 * next to one moves the nearest unlocked slice on that side instead, carrying the locked one along.
 */

/** 100%, in tenths of a percent. */
export const WHOLE = 1000;
/** A drag or arrow key moves 1%; with Shift, 0.1%. */
export const STEP = 10;
export const FINE_STEP = 1;

/**
 * Splits `total` across weights in proportion, as whole units that sum to exactly `total`
 * (largest remainder; ties go to the earlier weight). Weights that are all zero split evenly.
 */
export function apportion(weights: number[], total: number): number[] {
  if (weights.length === 0) {
    return [];
  }
  const sum = weights.reduce((a, b) => a + b, 0);
  // Rounded off at 1e-9 so float noise (0.29 * 1000 = 289.999...) doesn't cost a unit.
  const shares = (sum > 0 ? weights.map(w => (w / sum) * total) : weights.map(() => total / weights.length))
    .map(s => Math.round(s * 1e9) / 1e9);
  const floors = shares.map(Math.floor);
  let left = total - floors.reduce((a, b) => a + b, 0);
  const order = shares.map((s, i) => ({ i, rest: s - Math.floor(s) })).sort((a, b) => b.rest - a.rest || a.i - b.i);
  for (const { i } of order) {
    if (left <= 0) {
      break;
    }
    floors[i]++;
    left--;
  }
  return floors;
}

/** Targets matching the current allocation, from each slice's share (0-1). */
export function targetsFromShares(shares: number[]): number[] {
  return apportion(shares, WHOLE);
}

/** Where boundary i sits: the sum of the targets up to and including slice i. */
export function boundaryPosition(targets: number[], boundary: number): number {
  let sum = 0;
  for (let i = 0; i <= boundary; i++) {
    sum += targets[i];
  }
  return sum;
}

/** The unlocked slices a boundary actually trades between: the nearest on each side, or null. */
export function boundarySides(locked: boolean[], boundary: number): { before: number; after: number } | null {
  let before = boundary;
  while (before >= 0 && locked[before]) {
    before--;
  }
  let after = boundary + 1;
  while (after < locked.length && locked[after]) {
    after++;
  }
  return before >= 0 && after < locked.length ? { before, after } : null;
}

/** Moves a boundary by `delta` tenths (positive is clockwise), stopping when a side reaches 0%. */
export function moveBoundary(targets: number[], locked: boolean[], boundary: number, delta: number): number[] {
  const sides = boundarySides(locked, boundary);
  if (!sides || delta === 0) {
    return targets;
  }
  const { before, after } = sides;
  const moved = delta > 0 ? Math.min(delta, targets[after]) : Math.max(delta, -targets[before]);
  if (moved === 0) {
    return targets;
  }
  const next = [...targets];
  next[before] += moved;
  next[after] -= moved;
  return next;
}

/**
 * Drags a boundary towards `position` (tenths clockwise from 12 o'clock), snapping the slice
 * it grows or shrinks to a multiple of `step`, and stopping when either side reaches 0%.
 */
export function dragBoundary(targets: number[], locked: boolean[], boundary: number, position: number, step: number): number[] {
  const sides = boundarySides(locked, boundary);
  if (!sides) {
    return targets;
  }
  const { before, after } = sides;
  // The slice before the boundary starts here, and any locked slices it carries sit between them.
  const start = before > 0 ? boundaryPosition(targets, before - 1) : 0;
  const carried = boundaryPosition(targets, boundary) - boundaryPosition(targets, before);
  const wanted = Math.round((position - start - carried) / step) * step;
  const clamped = Math.min(Math.max(wanted, 0), targets[before] + targets[after]);
  return moveBoundary(targets, locked, boundary, clamped - targets[before]);
}

/**
 * Sets one slice's target (tenths, clamped to what the locked slices leave), and spreads the
 * difference over the other unlocked slices in proportion to their targets. `total` is what the
 * targets sum to: 100% by default, or e.g. a basket's amount in cents.
 */
export function setTarget(targets: number[], locked: boolean[], index: number, value: number, total = WHOLE): number[] {
  const others = targets.map((_, i) => i).filter(i => i !== index && !locked[i]);
  if (locked[index] || others.length === 0) {
    return targets;
  }
  const lockedSum = targets.reduce((sum, t, i) => (i !== index && locked[i] ? sum + t : sum), 0);
  const available = total - lockedSum;
  const own = Math.min(Math.max(Math.round(value), 0), available);
  if (own === targets[index]) {
    return targets;
  }
  const spread = apportion(others.map(i => targets[i]), available - own);
  const next = [...targets];
  next[index] = own;
  others.forEach((i, k) => { next[i] = spread[k]; });
  return next;
}

/**
 * Gives every unlocked holding the same target from what cash and the locked holdings leave.
 * Cash keeps its target, so the client's choice of cash to hold survives.
 */
export function equalWeight(targets: number[], locked: boolean[], isHolding: boolean[]): number[] {
  const open = targets.map((_, i) => i).filter(i => isHolding[i] && !locked[i]);
  if (open.length === 0) {
    return targets;
  }
  const kept = targets.reduce((sum, t, i) => (open.includes(i) ? sum : sum + t), 0);
  const even = apportion(open.map(() => 1), WHOLE - kept);
  const next = [...targets];
  open.forEach((i, k) => { next[i] = even[k]; });
  return next;
}

/** The target a newly added stock starts with: 5%. */
export const NEW_SLICE_TARGET = 50;

/**
 * Takes `amount` tenths evenly from the unlocked slices other than `except`: each gives the same,
 * a slice that runs out gives what it has and the others make up the rest. Returns the new targets
 * and how much was taken, which is less than asked only when the unlocked slices run dry.
 */
export function takeEvenly(targets: number[], locked: boolean[], except: number, amount: number): { targets: number[]; taken: number } {
  const next = [...targets];
  let left = amount;
  let donors = next.map((_, i) => i).filter(i => i !== except && !locked[i] && next[i] > 0);
  while (left > 0 && donors.length > 0) {
    const asks = apportion(donors.map(() => 1), left);
    donors.forEach((i, k) => {
      const given = Math.min(asks[k], next[i]);
      next[i] -= given;
      left -= given;
    });
    donors = donors.filter(i => next[i] > 0);
  }
  return { targets: next, taken: amount - left };
}

/**
 * Adds a slice at `index` (shifting the rest along), with `target` tenths taken evenly from the
 * unlocked slices. Returns the targets and the new slice's place in them.
 */
export function addSlice(targets: number[], locked: boolean[], index: number, target = NEW_SLICE_TARGET): { targets: number[]; locked: boolean[] } {
  const withSlice = [...targets.slice(0, index), 0, ...targets.slice(index)];
  const withLock = [...locked.slice(0, index), false, ...locked.slice(index)];
  const { targets: next, taken } = takeEvenly(withSlice, withLock, index, target);
  next[index] = taken;
  return { targets: next, locked: withLock };
}

/**
 * Removes the slice at `index`, giving its target back evenly to the unlocked slices; to every
 * slice when all of them are locked, so the targets still sum to 100%.
 */
export function removeSlice(targets: number[], locked: boolean[], index: number): { targets: number[]; locked: boolean[] } {
  const rest = targets.filter((_, i) => i !== index);
  const restLocked = locked.filter((_, i) => i !== index);
  const open = rest.map((_, i) => i).filter(i => !restLocked[i]);
  const takers = open.length ? open : rest.map((_, i) => i);
  const gifts = apportion(takers.map(() => 1), targets[index]);
  takers.forEach((i, k) => { rest[i] += gifts[k]; });
  return { targets: rest, locked: restLocked };
}

/**
 * Whether a target is just the current share rounded to a tenth, so reaching it trades nothing.
 * Within a whole tenth, since apportioning can round a share either way to make 100%.
 */
export function onTarget(tenths: number, share: number): boolean {
  return Math.abs(tenths - share * WHOLE) < 1;
}

/** Dollars to buy (positive) or sell (negative) to take a slice from its share to its target. */
export function targetChange(tenths: number, share: number, total: number): number {
  return onTarget(tenths, share) ? 0 : (tenths / WHOLE - share) * total;
}

/** 25% · 33.3% · 0%: a target, with its tenth only when it has one. */
export function formatTarget(tenths: number): string {
  return tenths % 10 === 0 ? `${tenths / 10}%` : `${(tenths / 10).toFixed(1)}%`;
}

/**
 * Reads a typed percentage ("25", "33.3", "12.5%") as tenths, rounded to the nearest tenth;
 * null when it isn't a number.
 */
export function parseTarget(text: string): number | null {
  const cleaned = text.trim().replace(/%$/, '').trim();
  if (!/^\d*\.?\d+$|^\d+\.$/.test(cleaned)) {
    return null;
  }
  return Math.round(parseFloat(cleaned) * 10);
}
