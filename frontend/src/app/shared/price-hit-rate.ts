import { Candle } from '../services/market-data';
import { OrderSide, OrderType } from '../services/order';

/** How many days of history the hint looks at. */
export const HIT_RATE_DAYS = 30;

export interface HitRateHint {
  text: string;
  /** Days the price was reached. */
  days: number;
  /** Days looked at. */
  total: number;
  /** Daily highs and lows can't say in what order they happened, so a trailing hint is a rough guide. */
  approximate: boolean;
}

export interface HitRateInput {
  symbol: string;
  side: OrderSide;
  type: OrderType;
  limitPrice: number | null;
  stopPrice: number | null;
  /** A trailing stop's trail, as a percent of the price or dollars. */
  trailPercent: number | null;
  trailAmount: number | null;
}

function money(value: number): string {
  return '$' + value.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
}

/**
 * How often the market reached the order's price over the recent daily candles. It describes the
 * (simulated) past and predicts nothing. Null when there is nothing to measure.
 */
export function hitRateHint(input: HitRateInput, candles: Candle[]): HitRateHint | null {
  const days = candles.slice(-HIT_RATE_DAYS);
  if (days.length === 0) {
    return null;
  }
  const { symbol, side, type } = input;
  const buy = side === 'BUY';
  const span = days.length >= HIT_RATE_DAYS ? `the last ${HIT_RATE_DAYS} days` : `the ${days.length} days available`;
  const finish = (count: number, phrase: string, approximate = false): HitRateHint => ({
    text: `${phrase} on ${count} of ${span}.`, days: count, total: days.length, approximate
  });

  if (type === 'LIMIT' && input.limitPrice != null && input.limitPrice > 0) {
    const limit = input.limitPrice;
    // A buy limit is reached when the price trades at or below it, a sell limit at or above.
    const count = days.filter(c => (buy ? c.low <= limit : c.high >= limit)).length;
    return finish(count, `${symbol} traded at or ${buy ? 'below' : 'above'} ${money(limit)}`);
  }
  if ((type === 'STOP' || type === 'STOP_LIMIT') && input.stopPrice != null && input.stopPrice > 0) {
    const stop = input.stopPrice;
    // A sell stop is reached when the price falls to it, a buy stop when it rises to it.
    const count = days.filter(c => (buy ? c.high >= stop : c.low <= stop)).length;
    return finish(count, `Your stop would have triggered`);
  }
  if (type === 'TRAILING_STOP') {
    const percent = input.trailPercent;
    const amount = input.trailAmount;
    if (percent != null && percent > 0) {
      const count = days.filter(c => c.open > 0 && (c.high - c.low) / c.open * 100 > percent).length;
      return finish(count, `${symbol} moved more than ${percent}% in a day`, true);
    }
    if (amount != null && amount > 0) {
      const count = days.filter(c => c.high - c.low > amount).length;
      return finish(count, `${symbol} moved more than ${money(amount)} in a day`, true);
    }
  }
  return null;
}

/** The sentence under the hint, which every one carries. */
export function hitRateDisclaimer(hint: HitRateHint): string {
  return `Simulated past prices, not a prediction.${hint.approximate ? ' Daily highs and lows can’t show the order of moves, so this is approximate.' : ''}`;
}
