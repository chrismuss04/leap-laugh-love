import { boldness, cone, endAboveProbability, logReturns, normalCdf, normalQuantile, touchProbability, volatility } from './forecast';

describe('forecast', () => {
  it('measures log returns and their volatility', () => {
    expect(logReturns([100, 0, 110])).toEqual([Math.log(1.1)]);
    expect(volatility([100, 101, 100, 101, 100])).toBeCloseTo(0.01149, 5);
    expect(volatility([100], 0.003)).toBe(0.003);
    expect(volatility([100, 100, 100], 0.003)).toBe(0.003);
  });

  it('has a normal CDF and quantile that agree', () => {
    expect(normalCdf(0)).toBeCloseTo(0.5, 7);
    expect(normalCdf(1.959964)).toBeCloseTo(0.975, 6);
    expect(normalCdf(-1)).toBeCloseTo(0.158655, 6);
    for (const p of [0.01, 0.05, 0.25, 0.5, 0.9, 0.995]) {
      expect(normalCdf(normalQuantile(p))).toBeCloseTo(p, 6);
    }
    expect(normalQuantile(0)).toBe(-Infinity);
  });

  it('opens a cone from the price, wider over time, the median just under it', () => {
    const steps = cone(100, 0.01, 4);
    expect(steps.map(s => s.step)).toEqual([0, 1, 2, 3, 4]);
    expect(steps[0].prices.every(p => Math.abs(p - 100) < 1e-9)).toBe(true);
    const width = (i: number) => steps[i].prices[4] - steps[i].prices[0];
    expect(width(4)).toBeGreaterThan(width(1));
    expect(steps[4].prices[2]).toBeCloseTo(100 * Math.exp(-0.5 * 0.0004), 9);
    expect(steps[4].prices).toEqual([...steps[4].prices].sort((a, b) => a - b));
  });

  it('gives the chance of touching a target, which only grows with time', () => {
    expect(touchProbability(100, 100, 0.01, 10)).toBe(1);
    const near = touchProbability(100, 102, 0.01, 10);
    expect(near).toBeGreaterThan(touchProbability(100, 105, 0.01, 10));
    expect(touchProbability(100, 102, 0.01, 40)).toBeGreaterThan(near);
    // With no drift, touching is about twice as likely as ending past the target.
    expect(near).toBeCloseTo(2 * endAboveProbability(100, 102, 0.01, 10), 1);
    expect(touchProbability(100, 98, 0.01, 10)).toBeGreaterThan(0.5);
    expect(touchProbability(100, 110, 0, 10)).toBe(0);
  });

  it('agrees with a simulated walk', () => {
    // A seeded walk, so the test never flakes.
    let seed = 7;
    const random = () => (seed = (seed * 16807) % 2147483647) / 2147483647;
    const gaussian = () => Math.sqrt(-2 * Math.log(random())) * Math.cos(2 * Math.PI * random());
    const sigma = 0.01;
    const steps = 20;
    let touched = 0;
    const runs = 4000;
    for (let run = 0; run < runs; run++) {
      let log = 0;
      for (let i = 0; i < steps * 10 && log < Math.log(1.03); i++) {
        log += -0.5 * sigma * sigma / 10 + (sigma / Math.sqrt(10)) * gaussian();
      }
      touched += log >= Math.log(1.03) ? 1 : 0;
    }
    // Sampling the walk only at sub-steps slightly undercounts touches.
    expect(touched / runs).toBeCloseTo(touchProbability(100, 103, sigma, steps), 1);
  });

  it('rates how bold a call is', () => {
    expect(boldness(100, 101, 0.01, 4).label).toBe('typical');
    expect(boldness(100, 103, 0.01, 4).label).toBe('bold');
    expect(boldness(100, 90, 0.01, 4)).toEqual({ sigmas: expect.closeTo(5.27, 2), label: 'moonshot' });
  });
});

describe('two exits', () => {
  it('gives the chance the upper exit fills first', async () => {
    const { hitsFirstProbability } = await import('./forecast');
    expect(hitsFirstProbability(100, 110, 95)).toBeCloseTo(1 / 3);
    expect(hitsFirstProbability(100, 101, 90)).toBeCloseTo(10 / 11);
    expect(hitsFirstProbability(120, 110, 95)).toBe(1);
    expect(hitsFirstProbability(100, 95, 95)).toBe(0);
  });
});
