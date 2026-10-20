import { HORIZONS, stepLabel } from './horizons';

describe('draw horizons', () => {
  it('runs from today to this month, only today\'s orders lasting the day', () => {
    expect(HORIZONS.map(h => `${h.id} ${h.timeInForce}`)).toEqual(['1D DAY', '1W GTC', '1M GTC']);
  });

  it('labels a step ahead in minutes, hours or days', () => {
    expect(stepLabel(0, 300)).toBe('Now');
    expect(stepLabel(9, 300)).toBe('+45m');
    expect(stepLabel(12, 300)).toBe('+1h');
    expect(stepLabel(40, 300)).toBe('+3h 20m');
    expect(stepLabel(22, 86400)).toBe('+22d');
  });
});
