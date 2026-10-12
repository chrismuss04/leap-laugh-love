import { ParamMap, Params } from '@angular/router';

/**
 * Staff Dashboards: the reporting period a commercial analyst looks at. Presets count back from
 * today; CUSTOM uses its own first and last day. Kept in the URL as ?period=, plus ?from= and ?to=
 * for a custom period, so a view can be bookmarked or shared.
 */
export type Period = '1W' | '1M' | '3M' | 'YTD' | '1Y' | 'CUSTOM';

export const PERIODS: readonly { period: Period; label: string }[] = [
  { period: '1W', label: 'Past week' },
  { period: '1M', label: 'Past month' },
  { period: '3M', label: 'Past 3 months' },
  { period: 'YTD', label: 'Year to date' },
  { period: '1Y', label: 'Past year' },
  { period: 'CUSTOM', label: 'Custom period' }
];

/** Left out of the URL, so the dashboard's plain address shows this. */
export const DEFAULT_PERIOD: Period = '1M';

export interface PeriodFilter {
  period: Period;
  /** Custom period only: first day, as yyyy-mm-dd. */
  from: string | null;
  /** Custom period only: last day, as yyyy-mm-dd. */
  to: string | null;
}

/** First and last day of a period, inclusive, as yyyy-mm-dd: what a report queries by. */
export interface DateRange {
  from: string;
  to: string;
}

export const DEFAULT_PERIOD_FILTER: PeriodFilter = { period: DEFAULT_PERIOD, from: null, to: null };

/**
 * Reads the period from the URL. Anything unreadable, including a custom period missing a day or
 * running backwards, falls back to the default rather than showing an error for a hand-edited link.
 */
export function parsePeriod(params: ParamMap): PeriodFilter {
  const period = params.get('period');
  if (period === 'CUSTOM') {
    const from = validDay(params.get('from'));
    const to = validDay(params.get('to'));
    return from && to && from <= to ? { period, from, to } : DEFAULT_PERIOD_FILTER;
  }
  const preset = PERIODS.find(p => p.period === period && p.period !== 'CUSTOM');
  return preset ? { period: preset.period, from: null, to: null } : DEFAULT_PERIOD_FILTER;
}

/** The query params for a period; null clears a param, so the default leaves a clean URL. */
export function periodParams(filter: PeriodFilter): Params {
  if (filter.period === 'CUSTOM') {
    return { period: 'CUSTOM', from: filter.from, to: filter.to };
  }
  return { period: filter.period === DEFAULT_PERIOD ? null : filter.period, from: null, to: null };
}

/** The days a period covers, counting presets back from today. */
export function resolvePeriod(filter: PeriodFilter, today = new Date()): DateRange {
  const to = toDay(today);
  switch (filter.period) {
    case 'CUSTOM': return { from: filter.from!, to: filter.to! };
    case '1W': return { from: toDay(addDays(today, -7)), to };
    case '1M': return { from: toDay(addMonths(today, -1)), to };
    case '3M': return { from: toDay(addMonths(today, -3)), to };
    case '1Y': return { from: toDay(addMonths(today, -12)), to };
    case 'YTD': return { from: `${today.getFullYear()}-01-01`, to };
  }
}

/** A yyyy-mm-dd day that exists on the calendar, or null. */
export function validDay(value: string | null): string | null {
  if (!value || !/^\d{4}-\d{2}-\d{2}$/.test(value)) return null;
  const [year, month, day] = value.split('-').map(Number);
  const date = new Date(year, month - 1, day);
  return date.getFullYear() === year && date.getMonth() === month - 1 && date.getDate() === day ? value : null;
}

/** A local date as yyyy-mm-dd. */
export function toDay(date: Date): string {
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`;
}

function addDays(date: Date, days: number): Date {
  return new Date(date.getFullYear(), date.getMonth(), date.getDate() + days);
}

// Clamped to the month's last day, so a month before 31 March is 28 or 29 February, not 3 March.
function addMonths(date: Date, months: number): Date {
  const first = new Date(date.getFullYear(), date.getMonth() + months, 1);
  const lastDay = new Date(first.getFullYear(), first.getMonth() + 1, 0).getDate();
  return new Date(first.getFullYear(), first.getMonth(), Math.min(date.getDate(), lastDay));
}
