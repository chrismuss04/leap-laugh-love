import { DataPoint, resample, smooth } from './stroke';

/**
 * Draw Your Trade: ready-made shapes to start from. They're the keyboard's way to draw, and a sure
 * thing when a shaky trackpad stroke won't do.
 *
 * Plain TypeScript with no Angular dependencies. Each shape is a few control points - how far along
 * the future (0-1) and how much of the move (-1 to 1) - smoothed into a line from now.
 */

export type PresetId = 'rally' | 'dip' | 'breakout' | 'fade';

export interface Preset {
  id: PresetId;
  label: string;
  /** What the shape says, for its button's title and screen readers. */
  hint: string;
  points: readonly [number, number][];
}

export const PRESETS: readonly Preset[] = [
  { id: 'rally', label: 'Rally', hint: 'Climbs steadily from here',
    points: [[0, 0], [0.2, 0.15], [0.4, 0.1], [0.65, 0.55], [0.85, 0.8], [1, 1]] },
  { id: 'dip', label: 'Dip & rip', hint: 'Dips first, then climbs past today',
    points: [[0, 0], [0.18, -0.45], [0.33, -0.6], [0.5, -0.3], [0.75, 0.5], [1, 0.9]] },
  { id: 'breakout', label: 'Breakout', hint: 'Drifts sideways, then breaks higher',
    points: [[0, 0], [0.15, 0.04], [0.3, -0.03], [0.45, 0.04], [0.6, 0.12], [0.8, 0.8], [1, 1.1]] },
  { id: 'fade', label: 'Fade', hint: 'Pops up, then slides lower',
    points: [[0, 0], [0.2, 0.35], [0.35, 0.5], [0.55, 0.2], [0.8, -0.5], [1, -0.8]] }
];

/** How much of the future a preset reaches across. */
const REACH = 0.85;

/**
 * How far a preset moves, as a fraction of the price: about one and a half times the walk's spread
 * over the reach, so a shape looks like this stock, kept between 0.6% and 8%.
 */
export function presetMove(sigma: number, steps: number): number {
  const spread = sigma * Math.sqrt(Math.max(steps * REACH, 1));
  return Math.min(Math.max(1.6 * spread, 0.006), 0.08);
}

/** A preset as a line from now at `price`, across most of `steps`. */
export function presetLine(preset: Preset, price: number, steps: number, sigma: number): DataPoint[] {
  const reach = Math.max(steps * REACH, 1);
  const move = presetMove(sigma, steps);
  // Smoothed in step-by-percent space, so the curve's shape doesn't depend on the price's size.
  const control = preset.points.map(([t, m]) => ({ x: t * reach, y: m * move * 100 }));
  const curve = smooth(control, 3).map(p => ({ step: p.x, price: price * (1 + p.y / 100) }));
  curve[0] = { step: 0, price };
  return resample(curve, Math.max(reach / 60, 0.1));
}

/** A small icon of the shape, as an SVG path in a `width` by `height` box. */
export function presetIcon(preset: Preset, width = 28, height = 14): string {
  const curve = smooth(preset.points.map(([t, m]) => ({ x: t, y: m })), 2);
  const low = Math.min(...curve.map(p => p.y));
  const high = Math.max(...curve.map(p => p.y));
  const span = high - low || 1;
  const f = (n: number) => Math.round(n * 10) / 10;
  return curve
    .map((p, i) => `${i ? 'L' : 'M'}${f(1 + p.x * (width - 2))},${f(1 + ((high - p.y) / span) * (height - 2))}`)
    .join('');
}
