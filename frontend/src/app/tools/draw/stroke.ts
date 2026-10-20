/**
 * Draw Your Trade: the drawn line, from pointer samples to a smooth path and a price series.
 *
 * Plain TypeScript with no Angular dependencies. The chart's x axis counts candles - 0 is now,
 * negative is history, positive is the future being drawn - so time runs evenly, with no gaps for
 * nights and weekends, and a step is the same width whatever the horizon.
 */

/** A point in pixels, as the pointer reports it. */
export interface PixelPoint {
  x: number;
  y: number;
}

/** A point on the chart: how many candles from now, and a price. */
export interface DataPoint {
  step: number;
  price: number;
}

/** Where the chart sits in pixels and what it spans in steps and price. */
export interface ChartFrame {
  left: number;
  right: number;
  top: number;
  bottom: number;
  /** The step at the left edge (negative: the history shown). */
  fromStep: number;
  /** The step at the right edge: the end of the future that can be drawn. */
  toStep: number;
  minPrice: number;
  maxPrice: number;
}

const lerp = (a: number, b: number, t: number) => a + (b - a) * t;

export function toPixel(frame: ChartFrame, point: DataPoint): PixelPoint {
  const tx = (point.step - frame.fromStep) / (frame.toStep - frame.fromStep || 1);
  const ty = (point.price - frame.minPrice) / (frame.maxPrice - frame.minPrice || 1);
  return { x: lerp(frame.left, frame.right, tx), y: lerp(frame.bottom, frame.top, ty) };
}

export function toData(frame: ChartFrame, pixel: PixelPoint): DataPoint {
  const tx = (pixel.x - frame.left) / (frame.right - frame.left || 1);
  const ty = (frame.bottom - pixel.y) / (frame.bottom - frame.top || 1);
  return { step: lerp(frame.fromStep, frame.toStep, tx), price: lerp(frame.minPrice, frame.maxPrice, ty) };
}

/**
 * A price range to chart: the prices given, padded so the line never touches the top or bottom,
 * and never narrower than `minSpan` of their middle so a quiet stock isn't drawn as a cliff.
 */
export function priceRange(prices: number[], padding = 0.12, minSpan = 0.02): { min: number; max: number } {
  const finite = prices.filter(p => Number.isFinite(p) && p > 0);
  if (!finite.length) {
    return { min: 0, max: 1 };
  }
  let min = Math.min(...finite);
  let max = Math.max(...finite);
  const middle = (min + max) / 2;
  const span = Math.max(max - min, middle * minSpan);
  min = middle - span / 2 - span * padding;
  max = middle + span / 2 + span * padding;
  return { min: Math.max(min, 0), max };
}

/**
 * The stroke with one more pointer sample. A stroke only moves forward in time from its anchor
 * (now), so a sample at or left of the anchor is ignored, and moving back over the line rubs out
 * what was drawn past the pointer - retracing is how a client fixes a stroke.
 */
export function extendStroke(stroke: readonly PixelPoint[], sample: PixelPoint, anchor: PixelPoint): PixelPoint[] {
  if (!Number.isFinite(sample.x) || !Number.isFinite(sample.y) || sample.x <= anchor.x) {
    return stroke.length ? [...stroke] : [anchor];
  }
  const kept = (stroke.length ? stroke : [anchor]).filter((p, i) => i === 0 || p.x < sample.x);
  return [...kept, sample];
}

/**
 * The stroke with points closer than `minGap` pixels to the last kept one dropped, keeping its
 * ends: pointer samples arrive far denser than a smooth line needs.
 */
export function thin<T extends PixelPoint>(points: readonly T[], minGap = 4): T[] {
  if (points.length < 3) {
    return [...points];
  }
  const kept: T[] = [points[0]];
  for (let i = 1; i < points.length - 1; i++) {
    const last = kept[kept.length - 1];
    if (Math.hypot(points[i].x - last.x, points[i].y - last.y) >= minGap) {
      kept.push(points[i]);
    }
  }
  kept.push(points[points.length - 1]);
  return kept;
}

/**
 * Chaikin corner cutting: each pass replaces every corner with two points a quarter of the way
 * along its sides. The ends stay put, and a line that only moves forward in x still does.
 */
export function smooth<T extends PixelPoint>(points: readonly T[], passes = 2): PixelPoint[] {
  let current: PixelPoint[] = points.map(p => ({ x: p.x, y: p.y }));
  for (let pass = 0; pass < passes && current.length > 2; pass++) {
    const next: PixelPoint[] = [current[0]];
    for (let i = 0; i < current.length - 1; i++) {
      const a = current[i];
      const b = current[i + 1];
      next.push({ x: lerp(a.x, b.x, 0.25), y: lerp(a.y, b.y, 0.25) }, { x: lerp(a.x, b.x, 0.75), y: lerp(a.y, b.y, 0.75) });
    }
    next.push(current[current.length - 1]);
    current = next;
  }
  return current;
}

/**
 * The line's price at every whole step from its first point to its last, read off between the
 * points either side. The line's points must move forward in step, as a stroke does.
 */
export function resample(points: readonly DataPoint[], every = 1): DataPoint[] {
  if (points.length < 2 || every <= 0) {
    return points.map(p => ({ ...p }));
  }
  const first = points[0].step;
  const last = points[points.length - 1].step;
  const out: DataPoint[] = [];
  let j = 0;
  for (let step = first; step <= last + 1e-9; step += every) {
    while (j < points.length - 2 && points[j + 1].step < step) {
      j++;
    }
    const a = points[j];
    const b = points[j + 1];
    const t = b.step === a.step ? 0 : Math.min(Math.max((step - a.step) / (b.step - a.step), 0), 1);
    out.push({ step, price: lerp(a.price, b.price, t) });
  }
  if (out[out.length - 1].step < last - 1e-9) {
    out.push({ ...points[points.length - 1] });
  }
  return out;
}

/**
 * An SVG path through the points as a Catmull-Rom spline, so a handful of points still draws a
 * smooth curve. `tension` 0 is a straight polyline; 1 the standard spline.
 */
export function splinePath(points: readonly PixelPoint[], tension = 1): string {
  if (!points.length) {
    return '';
  }
  const f = (n: number) => Math.round(n * 100) / 100;
  let d = `M${f(points[0].x)},${f(points[0].y)}`;
  for (let i = 0; i < points.length - 1; i++) {
    const p0 = points[Math.max(i - 1, 0)];
    const p1 = points[i];
    const p2 = points[i + 1];
    const p3 = points[Math.min(i + 2, points.length - 1)];
    const c1 = { x: p1.x + ((p2.x - p0.x) / 6) * tension, y: p1.y + ((p2.y - p0.y) / 6) * tension };
    const c2 = { x: p2.x - ((p3.x - p1.x) / 6) * tension, y: p2.y - ((p3.y - p1.y) / 6) * tension };
    d += ` C${f(c1.x)},${f(c1.y)} ${f(c2.x)},${f(c2.y)} ${f(p2.x)},${f(p2.y)}`;
  }
  return d;
}

/** The stroke's length in pixels, for drawing it in with a dash offset. */
export function strokeLength(points: readonly PixelPoint[]): number {
  let length = 0;
  for (let i = 1; i < points.length; i++) {
    length += Math.hypot(points[i].x - points[i - 1].x, points[i].y - points[i - 1].y);
  }
  return length;
}

/**
 * Round price levels for an axis across a range: about `count` of them, spaced 1, 2 or 5 times a
 * power of ten apart.
 */
export function niceTicks(min: number, max: number, count = 5): number[] {
  const span = max - min;
  if (!(span > 0) || !Number.isFinite(span)) {
    return [];
  }
  const power = 10 ** Math.floor(Math.log10(span / count));
  // The step whose number of ticks comes nearest the count asked for.
  const step = [1, 2, 5, 10]
    .map(m => m * power)
    .reduce((best, s) => (Math.abs(span / s - count) < Math.abs(span / best - count) ? s : best));
  const ticks: number[] = [];
  for (let n = Math.ceil(min / step - 1e-9); n * step <= max + 1e-9; n++) {
    ticks.push(Number((n * step).toFixed(10)));
  }
  return ticks;
}
