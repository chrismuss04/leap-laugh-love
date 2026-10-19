import { RecapPayload } from '../services/recap';

/** The story's cards. */
export type RecapCard = 'intro' | 'money' | 'tickers' | 'gainer' | 'bench' | 'tops';

/**
 * The recap's cards, in order. Every one plays, whatever the month had: a card with nothing to
 * show says so rather than being skipped. Only a month with nothing in it at all gets none (see
 * {@link isEmptyMonth}).
 */
export const RECAP_CARDS: readonly RecapCard[] = ['intro', 'money', 'tickers', 'gainer', 'bench', 'tops'];

/** No fills in the month and nothing held through it that could be priced. */
export function isEmptyMonth(recap: RecapPayload): boolean {
  return recap.quiet && recap.tradingReturn.returnPct === null;
}

export const CARD_LABELS: Record<RecapCard, string> = {
  intro: 'Intro',
  money: 'Trading volume',
  tickers: 'Top tickers',
  gainer: 'Top gainer',
  bench: 'Versus the S&P 500',
  tops: 'The tops'
};

function monthDate(month: string): Date {
  const [year, mon] = month.split('-').map(Number);
  return new Date(Date.UTC(year, mon - 1, 1));
}

/** '2026-09' -> 'September' */
export function monthName(month: string): string {
  return monthDate(month).toLocaleString('en-US', { month: 'long', timeZone: 'UTC' });
}

/** '2026-09' -> 'Sep 2026' */
export function monthShort(month: string): string {
  return monthDate(month).toLocaleString('en-US', { month: 'short', year: 'numeric', timeZone: 'UTC' });
}

/** '2026-09' -> '2026' */
export function monthYear(month: string): string {
  return month.slice(0, 4);
}

const wholeDollars = new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD', maximumFractionDigits: 0 });

/** $71,169 - the cards round to the dollar. */
export function formatWholeMoney(value: number): string {
  return wholeDollars.format(value);
}

/** +$2,940 / −$2,940 */
export function formatSignedWholeMoney(value: number): string {
  const rounded = Math.round(value);
  return sign(rounded) + wholeDollars.format(Math.abs(rounded));
}

/** +6.8% / −6.8% at the given places; unsigned when zero. */
export function formatSignedPercentTo(value: number, places: number): string {
  const rounded = Number(value.toFixed(places));
  return sign(rounded) + Math.abs(rounded).toFixed(places) + '%';
}

/** 4.7 points, 1 point */
export function formatPoints(value: number): string {
  const points = Math.abs(value).toFixed(1);
  return `${points} ${points === '1.0' ? 'point' : 'points'}`;
}

/** 1 filled order, 41 filled orders */
export function plural(count: number, one: string, many = one + 's'): string {
  return `${count.toLocaleString('en-US')} ${count === 1 ? one : many}`;
}

function sign(value: number): string {
  return value > 0 ? '+' : value < 0 ? '−' : '';
}

/**
 * An SVG path through the values, scaled to fill the box from (x0, y0) of width w and height h,
 * highest value at the top. Empty with fewer than two values.
 */
export function linePath(values: number[], x0: number, y0: number, w: number, h: number): string {
  if (values.length < 2) {
    return '';
  }
  const lo = Math.min(...values);
  const span = Math.max(...values) - lo || 1;
  return values
    .map((v, k) => `${k ? 'L' : 'M'}${(x0 + (k * w) / (values.length - 1)).toFixed(1)},${(y0 + h - ((v - lo) / span) * h).toFixed(1)}`)
    .join('');
}

/**
 * Bar widths, as percentages, for the client's return beside the index's: the larger move fills
 * its bar and the other is drawn to scale. Both are measured by size, whatever the direction.
 */
export function benchBars(mine: number, index: number): { mine: number; index: number } {
  const most = Math.max(Math.abs(mine), Math.abs(index));
  if (most === 0) {
    return { mine: 0, index: 0 };
  }
  return { mine: (Math.abs(mine) / most) * 100, index: (Math.abs(index) / most) * 100 };
}
