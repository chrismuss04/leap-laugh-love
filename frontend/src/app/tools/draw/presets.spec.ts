import { interpret } from './interpret';
import { PRESETS, presetIcon, presetLine, presetMove } from './presets';

describe('draw presets', () => {
  const preset = (id: string) => PRESETS.find(p => p.id === id)!;

  it('moves about as much as the stock does, within limits', () => {
    expect(presetMove(0.002, 72)).toBeCloseTo(1.6 * 0.002 * Math.sqrt(72 * 0.85), 6);
    expect(presetMove(0, 72)).toBe(0.006);
    expect(presetMove(0.1, 72)).toBe(0.08);
  });

  it('starts every shape at the price now, and moves forward from it', () => {
    for (const p of PRESETS) {
      const line = presetLine(p, 200, 72, 0.002);
      expect(line[0]).toEqual({ step: 0, price: 200 });
      expect(line[line.length - 1].step).toBeCloseTo(72 * 0.85);
      line.slice(1).forEach((point, i) => expect(point.step).toBeGreaterThan(line[i].step));
    }
  });

  it('reads as the trade each shape is named for', () => {
    const shape = (id: string, held = 0) => interpret(presetLine(preset(id), 200, 72, 0.002), 200, { held })!.shape;
    expect(shape('rally')).toBe('rally');
    expect(shape('dip')).toBe('dip-then-rally');
    expect(shape('breakout')).toBe('rally');
    expect(shape('fade', 5)).toBe('rally-then-fall');
  });

  it('draws a small icon of each shape inside its box', () => {
    const d = presetIcon(preset('dip'), 28, 14);
    const numbers = d.match(/-?\d+(\.\d+)?/g)!.map(Number);
    expect(d).toMatch(/^M/);
    expect(Math.min(...numbers)).toBeGreaterThanOrEqual(1);
    expect(Math.max(...numbers)).toBeLessThanOrEqual(27);
  });
});
