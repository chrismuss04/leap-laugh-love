/**
 * Rebalance Donut: an account's holdings and cash as donut slices, and where each slice's label
 * goes. Plain TypeScript, so the sizing and grouping rules are tested without a component.
 */

/** One holding to draw, priced live. */
export interface AllocationHolding {
  symbol: string;
  name: string;
  quantity: number;
  /** Latest price, or null while it's unknown; an unpriced holding can't be sized yet. */
  price: number | null;
}

export interface DonutSlice {
  /** The symbol, or 'cash'. */
  key: string;
  kind: 'holding' | 'cash';
  /** The symbol, or "Cash". */
  label: string;
  /** The instrument's name, or "Cash". */
  name: string;
  /** Shares held; null for cash. */
  shares: number | null;
  /** Quantity × live price, or the cash balance. */
  value: number;
  /** Fraction (0-1) of the account's value. */
  share: number;
  color: string;
  /** Too small to label on its own, so it shares the 'Other' label (but is still its own slice). */
  grouped: boolean;
  /** A stock the client added to the donut but doesn't own yet. */
  added?: boolean;
}

export interface Allocation {
  /** Every slice's value: holdings at live prices plus cash. */
  total: number;
  /** Holdings largest first, so the small ones sit together before cash, then cash. */
  slices: DonutSlice[];
  /** Holdings left out because their price isn't known yet. */
  unpriced: string[];
}

/** Slices under this share of the account are labelled together as 'Other'. */
export const OTHER_THRESHOLD = 0.03;

/**
 * Slice colours, ordered so neighbours differ in hue. Each clears 3:1 against the surface (WCAG
 * 1.4.11, non-text contrast), and slices are drawn with a gap so neighbours never need to contrast
 * with each other. Every slice is also labelled, so none is told apart by colour alone.
 */
export const SLICE_PALETTE = ['#5b9dff', '#f5b041', '#a78bfa', '#2dd4bf', '#f472b6', '#a3e635', '#fb8a5c', '#38bdf8'];
/** Cash is always this grey, which no holding uses. */
export const CASH_COLOR = '#8b8b99';

/**
 * @param options.alwaysCash give cash a slice even at $0, so a target can still be set for it.
 */
export function buildAllocation(holdings: AllocationHolding[], cash: number, options: { alwaysCash?: boolean } = {}): Allocation {
  const unpriced: string[] = [];
  const priced: { holding: AllocationHolding; value: number }[] = [];
  for (const holding of holdings) {
    if (holding.price == null || !(holding.price > 0) || !(holding.quantity > 0)) {
      if (holding.quantity > 0) {
        unpriced.push(holding.symbol);
      }
      continue;
    }
    priced.push({ holding, value: holding.quantity * holding.price });
  }
  // Largest first, ties by symbol, so the order (and each holding's colour) is stable.
  priced.sort((a, b) => b.value - a.value || a.holding.symbol.localeCompare(b.holding.symbol));

  const cashValue = Math.max(cash, 0);
  const total = priced.reduce((sum, p) => sum + p.value, 0) + cashValue;
  const shareOf = (value: number) => (total > 0 ? value / total : 0);

  const slices: DonutSlice[] = priced.map(({ holding, value }, i) => ({
    key: holding.symbol,
    kind: 'holding',
    label: holding.symbol,
    name: holding.name || holding.symbol,
    shares: holding.quantity,
    value,
    share: shareOf(value),
    color: SLICE_PALETTE[i % SLICE_PALETTE.length],
    grouped: shareOf(value) < OTHER_THRESHOLD
  }));
  // Cash keeps its own grey slice and label, however small; an empty account shows it alone.
  if (cashValue > 0 || slices.length === 0 || options.alwaysCash) {
    slices.push({
      key: 'cash', kind: 'cash', label: 'Cash', name: 'Cash', shares: null, value: cashValue,
      share: total > 0 ? shareOf(cashValue) : 1, color: CASH_COLOR, grouped: false
    });
  }
  return { total, slices, unpriced };
}

/** A label drawn beside the ring: one slice's, or the shared 'Other' label for the small ones. */
export interface DonutLabel {
  key: string;
  text: string;
  share: number;
  /** The slice keys it names. */
  keys: string[];
  /** Angle (radians, clockwise from 12 o'clock) of the arc it names. */
  angle: number;
}

/** Each slice's arc, in radians clockwise from 12 o'clock. */
export interface SliceArc {
  key: string;
  start: number;
  end: number;
}

export function sliceArcs(slices: Pick<DonutSlice, 'key' | 'share'>[]): SliceArc[] {
  let start = 0;
  return slices.map(slice => {
    const end = start + slice.share * Math.PI * 2;
    const arc = { key: slice.key, start, end };
    start = end;
    return arc;
  });
}

/**
 * One label per slice, except slices under the 'Other' threshold, which share one label for each
 * run of them (they're sorted together, so normally there's a single run).
 */
export function donutLabels(slices: DonutSlice[], arcs: SliceArc[]): DonutLabel[] {
  const labels: DonutLabel[] = [];
  let run: { slices: DonutSlice[]; start: number; end: number } | null = null;
  const flush = () => {
    if (run) {
      const share = run.slices.reduce((sum, s) => sum + s.share, 0);
      labels.push({
        key: 'other:' + run.slices[0].key, text: 'Other', share, keys: run.slices.map(s => s.key),
        angle: (run.start + run.end) / 2
      });
      run = null;
    }
  };
  slices.forEach((slice, i) => {
    const arc = arcs[i];
    if (slice.grouped) {
      run = run ? { ...run, slices: [...run.slices, slice], end: arc.end } : { slices: [slice], start: arc.start, end: arc.end };
      return;
    }
    flush();
    labels.push({ key: slice.key, text: slice.label, share: slice.share, keys: [slice.key], angle: (arc.start + arc.end) / 2 });
  });
  flush();
  return labels;
}

/** 60% · 4.2% · <0.1%: whole numbers once a share is big enough that a decimal is noise. */
export function formatShare(share: number): string {
  const percent = share * 100;
  if (percent > 0 && percent < 0.1) {
    return '<0.1%';
  }
  return percent >= 10 || percent === 0 ? `${Math.round(percent)}%` : `${percent.toFixed(1)}%`;
}

/**
 * Spreads labels down each side of the ring so none overlap: each starts level with its arc, then
 * labels too close to the one above are pushed down, and any pushed past the bottom are pushed
 * back up. Returns each label's y, in the same order.
 */
export function spreadLabels(wanted: number[], gap: number, top: number, bottom: number): number[] {
  const order = wanted.map((y, i) => ({ y, i })).sort((a, b) => a.y - b.y);
  const placed = order.map(o => Math.min(Math.max(o.y, top), bottom));
  for (let k = 1; k < placed.length; k++) {
    placed[k] = Math.max(placed[k], placed[k - 1] + gap);
  }
  const overflow = placed.length ? placed[placed.length - 1] - bottom : 0;
  if (overflow > 0) {
    placed[placed.length - 1] = bottom;
    for (let k = placed.length - 2; k >= 0; k--) {
      placed[k] = Math.min(placed[k], placed[k + 1] - gap);
    }
  }
  const result = new Array<number>(wanted.length);
  order.forEach((o, k) => { result[o.i] = placed[k]; });
  return result;
}
