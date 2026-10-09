import { convertToParamMap } from '@angular/router';
import { DEFAULT_PERIOD_FILTER, parsePeriod, periodParams, resolvePeriod, toDay, validDay } from './period';

describe('Reporting period', () => {
  const parse = (params: Record<string, string>) => parsePeriod(convertToParamMap(params));

  describe('parsePeriod', () => {
    it('defaults to the past month when the URL has no period', () => {
      expect(parse({})).toEqual(DEFAULT_PERIOD_FILTER);
      expect(DEFAULT_PERIOD_FILTER.period).toBe('1M');
    });

    for (const period of ['1W', '1M', '3M', 'YTD', '1Y']) {
      it(`reads the ${period} preset, ignoring stray days`, () => {
        expect(parse({ period, from: '2026-01-01', to: '2026-02-01' })).toEqual({ period: period as any, from: null, to: null });
      });
    }

    it('reads a custom period', () => {
      expect(parse({ period: 'CUSTOM', from: '2026-01-01', to: '2026-03-31' }))
        .toEqual({ period: 'CUSTOM', from: '2026-01-01', to: '2026-03-31' });
    });

    it('accepts a custom period of a single day', () => {
      expect(parse({ period: 'CUSTOM', from: '2026-01-01', to: '2026-01-01' }).period).toBe('CUSTOM');
    });

    it('falls back to the default for an unknown period', () => {
      expect(parse({ period: '5Y' })).toEqual(DEFAULT_PERIOD_FILTER);
    });

    it('falls back to the default for a custom period that is missing a day, impossible, or backwards', () => {
      expect(parse({ period: 'CUSTOM', from: '2026-01-01' })).toEqual(DEFAULT_PERIOD_FILTER);
      expect(parse({ period: 'CUSTOM', from: '2026-02-30', to: '2026-03-01' })).toEqual(DEFAULT_PERIOD_FILTER);
      expect(parse({ period: 'CUSTOM', from: '2026-03-01', to: '2026-01-01' })).toEqual(DEFAULT_PERIOD_FILTER);
    });
  });

  describe('periodParams', () => {
    it('clears every param for the default, so the dashboard URL stays plain', () => {
      expect(periodParams(DEFAULT_PERIOD_FILTER)).toEqual({ period: null, from: null, to: null });
    });

    it('keeps a preset and clears any custom days', () => {
      expect(periodParams({ period: 'YTD', from: null, to: null })).toEqual({ period: 'YTD', from: null, to: null });
    });

    it('keeps both days of a custom period', () => {
      expect(periodParams({ period: 'CUSTOM', from: '2026-01-01', to: '2026-01-31' }))
        .toEqual({ period: 'CUSTOM', from: '2026-01-01', to: '2026-01-31' });
    });

    it('round-trips through the URL', () => {
      const filter = { period: 'CUSTOM' as const, from: '2026-04-01', to: '2026-06-30' };
      expect(parsePeriod(convertToParamMap(periodParams(filter)))).toEqual(filter);
    });
  });

  describe('resolvePeriod', () => {
    const today = new Date(2026, 9, 8); // 8 October 2026

    it('counts presets back from today', () => {
      expect(resolvePeriod({ period: '1W', from: null, to: null }, today)).toEqual({ from: '2026-10-01', to: '2026-10-08' });
      expect(resolvePeriod({ period: '1M', from: null, to: null }, today)).toEqual({ from: '2026-09-08', to: '2026-10-08' });
      expect(resolvePeriod({ period: '3M', from: null, to: null }, today)).toEqual({ from: '2026-07-08', to: '2026-10-08' });
      expect(resolvePeriod({ period: '1Y', from: null, to: null }, today)).toEqual({ from: '2025-10-08', to: '2026-10-08' });
    });

    it('starts year to date on 1 January', () => {
      expect(resolvePeriod({ period: 'YTD', from: null, to: null }, today)).toEqual({ from: '2026-01-01', to: '2026-10-08' });
    });

    it('uses a custom period as given', () => {
      expect(resolvePeriod({ period: 'CUSTOM', from: '2026-02-01', to: '2026-02-14' }, today))
        .toEqual({ from: '2026-02-01', to: '2026-02-14' });
    });

    it('clamps to the end of a shorter month rather than spilling into the next', () => {
      expect(resolvePeriod({ period: '1M', from: null, to: null }, new Date(2026, 2, 31)).from).toBe('2026-02-28');
      expect(resolvePeriod({ period: '1Y', from: null, to: null }, new Date(2028, 1, 29)).from).toBe('2027-02-28');
    });
  });

  describe('validDay', () => {
    it('accepts real days and rejects anything else', () => {
      expect(validDay('2028-02-29')).toBe('2028-02-29');
      expect(validDay('2026-02-29')).toBeNull();
      expect(validDay('2026-1-1')).toBeNull();
      expect(validDay('')).toBeNull();
      expect(validDay(null)).toBeNull();
    });
  });

  it('formats local dates as yyyy-mm-dd', () => {
    expect(toDay(new Date(2026, 0, 5))).toBe('2026-01-05');
  });
});
