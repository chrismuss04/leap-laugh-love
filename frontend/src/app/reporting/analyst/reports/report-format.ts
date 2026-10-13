// Analyst reports: how the 12-month reports format months, money, counts and changes.

const monthFormat = new Intl.DateTimeFormat('en-US', { month: 'short', year: 'numeric', timeZone: 'UTC' });
const compactCurrency = new Intl.NumberFormat('en-US', {
  style: 'currency', currency: 'USD', notation: 'compact', maximumFractionDigits: 1
});
const whole = new Intl.NumberFormat('en-US');
const oneDecimal = new Intl.NumberFormat('en-US', { minimumFractionDigits: 1, maximumFractionDigits: 1 });

/** 'Sep 2026' for '2026-09'. Months are UTC, so formatted in UTC. */
export function monthLabel(month: string): string {
  const [year, monthNumber] = month.split('-').map(Number);
  return monthFormat.format(new Date(Date.UTC(year, monthNumber - 1, 1)));
}

/** $47.4M, $1.2K, $950 */
export function compactMoney(value: number | null | undefined): string {
  return value == null ? '—' : compactCurrency.format(value);
}

/** 1,234 */
export function count(value: number | null | undefined): string {
  return value == null ? '—' : whole.format(value);
}

/** 45.0% */
export function percent(value: number | null | undefined): string {
  return value == null ? '—' : `${oneDecimal.format(value)}%`;
}

/** +50.0% / −25.0% / 0.0%: always signed, so direction never relies on colour alone. */
export function change(value: number | null | undefined): string {
  if (value == null) {
    return '—';
  }
  const sign = value > 0 ? '+' : value < 0 ? '−' : '';
  return `${sign}${oneDecimal.format(Math.abs(value))}%`;
}

/** 'gain' | 'loss' | 'flat', for colouring a change. */
export function trend(value: number | null | undefined): 'gain' | 'loss' | 'flat' {
  return value == null || value === 0 ? 'flat' : value > 0 ? 'gain' : 'loss';
}

/** Changes at least this large (either way) are coloured; smaller ones stay muted, so colour means "look here". */
export const NOTABLE_CHANGE_PCT = 10;

/** 'gain' or 'loss' for a notable change, 'flat' for a small or missing one. */
export function notableTrend(value: number | null | undefined): 'gain' | 'loss' | 'flat' {
  return value == null || Math.abs(value) < NOTABLE_CHANGE_PCT ? 'flat' : trend(value);
}

/** value as a percentage of max, for a bar's width; 0 when there is nothing to scale against. */
export function barWidth(value: number | null | undefined, max: number): number {
  return value == null || max <= 0 ? 0 : Math.min(100, Math.max(0, (value / max) * 100));
}
