/**
 * Price protection: a market order is rejected if its fill price is more than this many percent
 * away - either way - from the price the client reviewed. Shared by the settings page and the
 * trade ticket so both describe it the same way.
 */

/** The choices offered as one-tap buttons, in percent. */
export const PROTECTION_PRESETS: readonly number[] = [0.25, 0.5, 1, 2];

/** The largest protection account-app and order-app accept, in percent. */
export const MAX_PROTECTION = 10;

const percent = new Intl.NumberFormat('en-US', { maximumFractionDigits: 2 });

/** 0.5 -> "0.5%", null -> "Off". */
export function formatProtection(value: number | null | undefined): string {
  return value == null ? 'Off' : `${percent.format(value)}%`;
}

/** The prices an order quoted at `price` may fill between under `value` percent of protection. */
export function protectionBand(price: number, value: number): { low: number; high: number } {
  const move = price * value / 100;
  return { low: price - move, high: price + move };
}

/**
 * Parses a typed protection, e.g. "0.75" or "1.5%". Returns null for an empty entry, or NaN when
 * it isn't a number from 0 to MAX_PROTECTION with at most two decimals.
 */
export function parseProtection(text: string): number | null {
  const trimmed = text.trim().replace(/%$/, '').trim();
  if (trimmed === '') {
    return null;
  }
  if (!/^\d+(\.\d{1,2})?$|^\.\d{1,2}$/.test(trimmed)) {
    return NaN;
  }
  const value = Number(trimmed);
  return value <= MAX_PROTECTION ? value : NaN;
}
