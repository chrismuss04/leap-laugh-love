import { ChartFrame, extendStroke, priceRange, resample, smooth, splinePath, strokeLength, toData, toPixel } from './stroke';

describe('draw stroke', () => {
  const frame: ChartFrame = { left: 0, right: 200, top: 0, bottom: 100, fromStep: -100, toStep: 100, minPrice: 90, maxPrice: 110 };

  it('maps steps and prices to pixels and back', () => {
    expect(toPixel(frame, { step: 0, price: 100 })).toEqual({ x: 100, y: 50 });
    expect(toPixel(frame, { step: 100, price: 110 })).toEqual({ x: 200, y: 0 });
    const back = toData(frame, { x: 150, y: 25 });
    expect(back.step).toBeCloseTo(50);
    expect(back.price).toBeCloseTo(105);
  });

  it('pads the price range, and widens a quiet one', () => {
    const range = priceRange([100, 110]);
    expect(range.min).toBeCloseTo(98.8);
    expect(range.max).toBeCloseTo(111.2);
    const quiet = priceRange([100, 100.01]);
    expect(quiet.max - quiet.min).toBeGreaterThan(2);
    expect(priceRange([])).toEqual({ min: 0, max: 1 });
  });

  describe('extending', () => {
    const anchor = { x: 100, y: 50 };

    it('starts at the anchor and only moves forward', () => {
      let stroke = extendStroke([], { x: 90, y: 10 }, anchor);
      expect(stroke).toEqual([anchor]);
      stroke = extendStroke(stroke, { x: 110, y: 40 }, anchor);
      stroke = extendStroke(stroke, { x: 120, y: 30 }, anchor);
      expect(stroke).toEqual([anchor, { x: 110, y: 40 }, { x: 120, y: 30 }]);
    });

    it('rubs out what was drawn past the pointer when it moves back', () => {
      const stroke = [anchor, { x: 110, y: 40 }, { x: 120, y: 30 }, { x: 130, y: 20 }];
      expect(extendStroke(stroke, { x: 115, y: 60 }, anchor)).toEqual([anchor, { x: 110, y: 40 }, { x: 115, y: 60 }]);
    });
  });

  it('smooths corners, keeping the ends and the forward direction', () => {
    const points = [{ x: 0, y: 0 }, { x: 10, y: 10 }, { x: 20, y: 0 }];
    const smoothed = smooth(points, 2);
    expect(smoothed[0]).toEqual({ x: 0, y: 0 });
    expect(smoothed[smoothed.length - 1]).toEqual({ x: 20, y: 0 });
    expect(Math.max(...smoothed.map(p => p.y))).toBeLessThan(10);
    smoothed.slice(1).forEach((p, i) => expect(p.x).toBeGreaterThanOrEqual(smoothed[i].x));
    expect(smooth([{ x: 0, y: 0 }, { x: 1, y: 1 }])).toEqual([{ x: 0, y: 0 }, { x: 1, y: 1 }]);
  });

  it('resamples a line at whole steps, keeping its end', () => {
    const line = resample([{ step: 0, price: 100 }, { step: 2, price: 102 }, { step: 2.5, price: 110 }]);
    expect(line).toEqual([{ step: 0, price: 100 }, { step: 1, price: 101 }, { step: 2, price: 102 }, { step: 2.5, price: 110 }]);
  });

  it('draws a spline through the points', () => {
    expect(splinePath([])).toBe('');
    expect(splinePath([{ x: 0, y: 0 }, { x: 10, y: 5 }])).toBe('M0,0 C1.67,0.83 8.33,4.17 10,5');
    expect(splinePath([{ x: 0, y: 0 }, { x: 10, y: 5 }], 0)).toBe('M0,0 C0,0 10,5 10,5');
  });

  it('measures a stroke', () => {
    expect(strokeLength([{ x: 0, y: 0 }, { x: 3, y: 4 }, { x: 3, y: 10 }])).toBe(11);
  });
});
