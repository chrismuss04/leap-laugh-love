/**
 * Draw Your Trade: how likely a drawn line is, on a random walk.
 *
 * Plain TypeScript with no Angular dependencies. Prices are modelled as geometric Brownian motion
 * with no drift - the same kind of walk market-data-app simulates - and a volatility measured from
 * the stock's recent candles. Time is counted in candles (steps), as the chart counts it.
 */

/** Natural-log returns from one close to the next; non-positive prices are skipped. */
export function logReturns(closes: readonly number[]): number[] {
  const priced = closes.filter(c => Number.isFinite(c) && c > 0);
  const returns: number[] = [];
  for (let i = 1; i < priced.length; i++) {
    returns.push(Math.log(priced[i] / priced[i - 1]));
  }
  return returns;
}

/**
 * Volatility per step: the sample standard deviation of the log returns, or `fallback` when there
 * are too few closes to measure one.
 */
export function volatility(closes: readonly number[], fallback = 0.002): number {
  const returns = logReturns(closes);
  if (returns.length < 2) {
    return fallback;
  }
  const mean = returns.reduce((sum, r) => sum + r, 0) / returns.length;
  const variance = returns.reduce((sum, r) => sum + (r - mean) ** 2, 0) / (returns.length - 1);
  const sigma = Math.sqrt(variance);
  return sigma > 0 ? sigma : fallback;
}

/** The standard normal CDF (Abramowitz & Stegun 7.1.26, error under 1.5e-7). */
export function normalCdf(x: number): number {
  const z = Math.abs(x) / Math.SQRT2;
  const t = 1 / (1 + 0.3275911 * z);
  const poly = t * (0.254829592 + t * (-0.284496736 + t * (1.421413741 + t * (-1.453152027 + t * 1.061405429))));
  const erf = 1 - poly * Math.exp(-z * z);
  return x >= 0 ? (1 + erf) / 2 : (1 - erf) / 2;
}

/** The standard normal quantile (Acklam's rational approximation, relative error under 1.2e-9). */
export function normalQuantile(p: number): number {
  if (p <= 0) {
    return -Infinity;
  }
  if (p >= 1) {
    return Infinity;
  }
  const a = [-39.69683028665376, 220.9460984245205, -275.9285104469687, 138.357751867269, -30.66479806614716, 2.506628277459239];
  const b = [-54.47609879822406, 161.5858368580409, -155.6989798598866, 66.80131188771972, -13.28068155288572];
  const c = [-0.007784894002430293, -0.3223964580411365, -2.400758277161838, -2.549732539343734, 4.374664141464968, 2.938163982698783];
  const d = [0.007784695709041462, 0.3224671290700398, 2.445134137142996, 3.754408661907416];
  const low = 0.02425;
  if (p < low) {
    const q = Math.sqrt(-2 * Math.log(p));
    return (((((c[0] * q + c[1]) * q + c[2]) * q + c[3]) * q + c[4]) * q + c[5]) / ((((d[0] * q + d[1]) * q + d[2]) * q + d[3]) * q + 1);
  }
  if (p > 1 - low) {
    return -normalQuantile(1 - p);
  }
  const q = p - 0.5;
  const r = q * q;
  return (((((a[0] * r + a[1]) * r + a[2]) * r + a[3]) * r + a[4]) * r + a[5]) * q
    / (((((b[0] * r + b[1]) * r + b[2]) * r + b[3]) * r + b[4]) * r + 1);
}

/** One step of the cone: the price at each of the cone's quantiles. */
export interface ConeStep {
  step: number;
  /** Prices in the order of the quantiles asked for. */
  prices: number[];
}

/** The 5th, 25th, 50th, 75th and 95th percentiles: an outer band, an inner band and the middle. */
export const CONE_QUANTILES: readonly number[] = [0.05, 0.25, 0.5, 0.75, 0.95];

/**
 * Where the price could be at each step from now to `steps`, at each quantile: the cone the chart
 * draws behind the line. With no drift the median sits a touch under today's price.
 */
export function cone(price: number, sigma: number, steps: number, quantiles: readonly number[] = CONE_QUANTILES,
                     every = 1): ConeStep[] {
  const z = quantiles.map(normalQuantile);
  const out: ConeStep[] = [];
  for (let step = 0; step <= steps + 1e-9; step += every) {
    const spread = sigma * Math.sqrt(step);
    out.push({ step, prices: z.map(zq => price * Math.exp(-0.5 * spread * spread + zq * spread)) });
  }
  return out;
}

/**
 * The chance the price reaches `target` at any point within `steps` - touching it is enough, as a
 * limit or take-profit needs. A target at or past the price is already reached.
 */
export function touchProbability(price: number, target: number, sigma: number, steps: number): number {
  if (!(price > 0) || !(target > 0)) {
    return 0;
  }
  const b = Math.log(target / price);
  if (b === 0) {
    return 1;
  }
  if (!(sigma > 0) || !(steps > 0)) {
    return 0;
  }
  // Log price is Brownian motion with drift nu; the reflection principle gives its first passage.
  const nu = -0.5 * sigma * sigma;
  const spread = sigma * Math.sqrt(steps);
  const mirror = Math.exp((2 * nu * b) / (sigma * sigma));
  const p = b > 0
    ? normalCdf((nu * steps - b) / spread) + mirror * normalCdf((-nu * steps - b) / spread)
    : normalCdf((b - nu * steps) / spread) + mirror * normalCdf((b + nu * steps) / spread);
  return Math.min(Math.max(p, 0), 1);
}

/** The chance the price is above `target` after exactly `steps`. */
export function endAboveProbability(price: number, target: number, sigma: number, steps: number): number {
  if (!(price > 0) || !(target > 0)) {
    return 0;
  }
  if (!(sigma > 0) || !(steps > 0)) {
    return price > target ? 1 : 0;
  }
  const spread = sigma * Math.sqrt(steps);
  return 1 - normalCdf((Math.log(target / price) + 0.5 * spread * spread) / spread);
}

export type Boldness = 'typical' | 'bold' | 'moonshot';

/**
 * How unusual a move to `target` within `steps` is, in standard deviations of the walk: within one
 * is typical, within two bold, past that a moonshot.
 */
export function boldness(price: number, target: number, sigma: number, steps: number): { sigmas: number; label: Boldness } {
  const spread = sigma * Math.sqrt(Math.max(steps, 0));
  const sigmas = spread > 0 && price > 0 && target > 0 ? Math.abs(Math.log(target / price)) / spread : 0;
  return { sigmas, label: sigmas <= 1 ? 'typical' : sigmas <= 2 ? 'bold' : 'moonshot' };
}
