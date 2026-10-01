const currency = new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD' });
const plain = new Intl.NumberFormat('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 });

/** $1,234.56 */
export function formatMoney(value: number | null | undefined): string {
  return value == null || Number.isNaN(value) ? '—' : currency.format(value);
}

/** +$1,234.56 / −$1,234.56 - always signed, so direction never relies on colour alone. */
export function formatSignedMoney(value: number | null | undefined): string {
  if (value == null || Number.isNaN(value)) {
    return '—';
  }
  const sign = value > 0 ? '+' : value < 0 ? '−' : '';
  return sign + currency.format(Math.abs(value));
}

/** +1.23% / −1.23% */
export function formatSignedPercent(value: number | null | undefined): string {
  if (value == null || !Number.isFinite(value)) {
    return '—';
  }
  const sign = value > 0 ? '+' : value < 0 ? '−' : '';
  return `${sign}${plain.format(Math.abs(value))}%`;
}

/** 4,812.10 - index levels are points, not dollars. */
export function formatNumber(value: number | null | undefined): string {
  return value == null || Number.isNaN(value) ? '—' : plain.format(value);
}

/** 'gain' | 'loss' | 'flat', for picking a colour class. */
export function direction(value: number | null | undefined): 'gain' | 'loss' | 'flat' {
  if (value == null || value === 0 || Number.isNaN(value)) {
    return 'flat';
  }
  return value > 0 ? 'gain' : 'loss';
}

/** Percentage change from a baseline, or null when there is no meaningful baseline. */
export function percentChange(current: number | null | undefined, baseline: number | null | undefined): number | null {
  if (current == null || baseline == null || baseline === 0) {
    return null;
  }
  return ((current - baseline) / Math.abs(baseline)) * 100;
}

/** Formats a phone number as it's typed: 5551234567 -> (555) 123-4567, dropping a leading US "1". */
export function formatPhone(value: string): string {
  const digits = value.replace(/\D/g, '').replace(/^1(?=\d{10}$)/, '').slice(0, 10);
  if (digits.length > 6) return `(${digits.slice(0, 3)}) ${digits.slice(3, 6)}-${digits.slice(6)}`;
  if (digits.length > 3) return `(${digits.slice(0, 3)}) ${digits.slice(3)}`;
  return digits.length ? `(${digits}` : '';
}
