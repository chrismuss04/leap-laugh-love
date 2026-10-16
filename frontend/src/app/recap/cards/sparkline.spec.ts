import { areaPath, linePath } from './sparkline';
import { count, percent2, points1, signedPercent1, signedWholeMoney, wholeMoney } from './recap-format';

describe('linePath', () => {
  it('spans the box: first value at the left, last at the right, low at the bottom, high at the top', () => {
    expect(linePath([10, 20, 15], 0, 0, 100, 50)).toBe('M0.0,50.0L50.0,0.0L100.0,25.0');
  });

  it('runs a flat series along the middle, and draws a single value as a flat line', () => {
    expect(linePath([5, 5], 10, 20, 80, 40)).toBe('M10.0,40.0L90.0,40.0');
    expect(linePath([7], 10, 20, 80, 40)).toBe('M10.0,40.0L90.0,40.0');
  });

  it('draws nothing with no values', () => {
    expect(linePath([], 0, 0, 100, 100)).toBe('');
    expect(areaPath('', 0, 100, 100)).toBe('');
  });

  it('closes an area down to the baseline', () => {
    expect(areaPath('M0.0,10.0L100.0,0.0', 0, 100, 90)).toBe('M0.0,10.0L100.0,0.0L100.0,90L0.0,90Z');
  });
});

describe('recap formats', () => {
  it('drops cents from big figures and signs them with a true minus', () => {
    expect(wholeMoney(71169.4)).toBe('$71,169');
    expect(signedWholeMoney(2940)).toBe('+$2,940');
    expect(signedWholeMoney(-2940.2)).toBe('−$2,940');
    expect(signedWholeMoney(0.3)).toBe('$0');
  });

  it('formats percentages and points', () => {
    expect(signedPercent1(6.83)).toBe('+6.8%');
    expect(signedPercent1(-12.25)).toBe('−12.3%');
    expect(signedPercent1(null)).toBe('—');
    expect(percent2(-18.4)).toBe('18.40%');
    expect(points1(-4.66)).toBe('4.7');
  });

  it('counts in the singular and plural', () => {
    expect(count(1, 'filled order')).toBe('1 filled order');
    expect(count(1041, 'filled order')).toBe('1,041 filled orders');
  });
});
