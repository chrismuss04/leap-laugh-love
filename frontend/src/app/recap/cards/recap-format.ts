// Number formats the recap cards use. Big figures drop the cents, as the Figma cards do; signs use a
// true minus so a loss never relies on colour alone.

const whole = new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD', maximumFractionDigits: 0 });
const oneDecimal = new Intl.NumberFormat('en-US', { minimumFractionDigits: 1, maximumFractionDigits: 1 });
const twoDecimals = new Intl.NumberFormat('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 });

const sign = (value: number): string => value > 0 ? '+' : value < 0 ? '−' : '';

/** $71,169 */
export function wholeMoney(value: number): string {
  return whole.format(value);
}

/** +$2,940 / −$2,940 */
export function signedWholeMoney(value: number): string {
  return sign(Math.round(value)) + whole.format(Math.abs(value));
}

/** +6.8% / −6.8%; a dash when there is no figure. */
export function signedPercent1(value: number | null | undefined): string {
  return value == null ? '—' : `${sign(value)}${oneDecimal.format(Math.abs(value))}%`;
}

/** 18.42% - unsigned, for figures a label already gives the direction of. */
export function percent2(value: number): string {
  return `${twoDecimals.format(Math.abs(value))}%`;
}

/** 4.7 */
export function points1(value: number): string {
  return oneDecimal.format(Math.abs(value));
}

/** "1 filled order", "41 filled orders" */
export function count(value: number, singular: string, plural = singular + 's'): string {
  return `${value.toLocaleString('en-US')} ${value === 1 ? singular : plural}`;
}
