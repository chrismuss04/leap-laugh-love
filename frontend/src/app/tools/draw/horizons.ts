import { CandleInterval } from '../../services/market-data';

/**
 * Draw Your Trade: how far ahead the client is drawing. The horizon sets the candle the chart
 * counts in, how much history is shown, how far the line can go, and how long the orders last.
 */
export interface DrawHorizon {
  id: '1D' | '1W' | '1M';
  label: string;
  interval: CandleInterval;
  /** Candles of history shown left of now. */
  historySteps: number;
  /** Candles that can be drawn right of now. */
  futureSteps: number;
  /** How long a limit order the line makes keeps working. */
  timeInForce: 'DAY' | 'GTC';
}

export const HORIZONS: readonly DrawHorizon[] = [
  { id: '1D', label: 'Today', interval: 300, historySteps: 96, futureSteps: 72, timeInForce: 'DAY' },
  { id: '1W', label: 'This week', interval: 3600, historySteps: 72, futureSteps: 40, timeInForce: 'GTC' },
  { id: '1M', label: 'This month', interval: 86400, historySteps: 60, futureSteps: 22, timeInForce: 'GTC' }
];

/** "+45m", "+3h 20m", "+4d": how far ahead a step is, counted in the horizon's candles. */
export function stepLabel(step: number, interval: number): string {
  const minutes = Math.round((step * interval) / 60);
  if (minutes <= 0) {
    return 'Now';
  }
  if (minutes < 60) {
    return `+${minutes}m`;
  }
  if (minutes < 24 * 60) {
    const hours = Math.floor(minutes / 60);
    const rest = minutes % 60;
    return rest ? `+${hours}h ${rest}m` : `+${hours}h`;
  }
  return `+${Math.round(minutes / (24 * 60))}d`;
}
