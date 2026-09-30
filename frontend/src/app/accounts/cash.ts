/**
 * Whether an amount of cash can be moved: at least one cent, and in whole cents, as account-app
 * requires. Compared with a tolerance because 0.29 * 100 is 28.999999999999996 in floating point.
 */
export function isCashAmount(amount: number | null | undefined): boolean {
  return amount != null && amount >= 0.01 && Math.abs(Math.round(amount * 100) - amount * 100) < 1e-6;
}
