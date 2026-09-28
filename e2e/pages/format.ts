/**
 * Parses the app's money/number text back to a number: "$1,234.56", "+$12.00", "−$3.10" (the app
 * uses a real minus sign, U+2212), "4,812.10". Returns null for the "—" placeholder.
 */
export function parseAmount(text: string | null): number | null {
  if (!text) {
    return null;
  }
  const trimmed = text.trim();
  if (trimmed === '—' || trimmed === '') {
    return null;
  }
  const negative = /^[−-]/.test(trimmed);
  const digits = trimmed.replace(/[^0-9.]/g, '');
  if (digits === '') {
    return null;
  }
  const value = Number(digits);
  return negative ? -value : value;
}

/** Money as the app shows it, e.g. 1234.5 -> "$1,234.50". */
export function money(value: number): string {
  return new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD' }).format(value);
}

/** Matches "$1,234.56"-shaped text. */
export const MONEY = /\$[\d,]+\.\d{2}/;
