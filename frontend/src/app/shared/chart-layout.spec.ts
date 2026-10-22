import { LEAD_IN_WIDTH, TradingHours, dayFrame, firstInDomain, placeByTime } from './chart-layout';

/** Wednesday 14 October 2026 (EDT): 4:00, 9:30, 16:00 and 20:00 ET. */
const HOURS: TradingHours = {
  preMarketOpen: '2026-10-14T08:00:00Z', regularOpen: '2026-10-14T13:30:00Z',
  regularClose: '2026-10-14T20:00:00Z', afterHoursClose: '2026-10-15T00:00:00Z'
};
const at = (iso: string) => Date.parse(iso);
/** Every 5 minutes from one time to another, inclusive. */
const every5 = (from: string, to: string) => {
  const times: number[] = [];
  for (let time = at(from); time <= at(to); time += 300_000) {
    times.push(time);
  }
  return times;
};

describe('chart layout', () => {
  describe('dayFrame', () => {
    // Tuesday's after-hours, then Wednesday's pre-market to 8:00 ET.
    const morning = [...every5('2026-10-13T23:00:00Z', '2026-10-14T00:00:00Z'), ...every5('2026-10-14T08:05:00Z', '2026-10-14T12:00:00Z')];

    it('frames pre-market up to the close, led into by the last hour before it', () => {
      expect(dayFrame(HOURS, 'PRE_MARKET', morning, at('2026-10-14T12:00:00Z'))).toEqual({
        start: at(HOURS.preMarketOpen), end: at(HOURS.regularClose), leadInFrom: at('2026-10-13T23:05:00Z')
      });
      // Nothing traded before the day: no lead-in.
      expect(dayFrame(HOURS, 'PRE_MARKET', morning.slice(13), at('2026-10-14T12:00:00Z')).leadInFrom).toBeNull();
    });

    it('frames regular hours, and after the close the open to the end of after-hours', () => {
      expect(dayFrame(HOURS, 'REGULAR', morning, 0)).toEqual({ start: at(HOURS.regularOpen), end: at(HOURS.regularClose) });
      expect(dayFrame(HOURS, 'AFTER_HOURS', morning, 0)).toEqual({ start: at(HOURS.regularOpen), end: at(HOURS.afterHoursClose) });
      expect(dayFrame(HOURS, 'CLOSED', morning, 0)).toEqual({ start: at(HOURS.regularOpen), end: at(HOURS.afterHoursClose) });
    });

    it('frames a day that traded past where the session says it is as a whole day', () => {
      const finished = [...morning, at('2026-10-14T21:00:00Z')];
      expect(dayFrame(HOURS, 'PRE_MARKET', finished, 0)).toEqual({ start: at(HOURS.regularOpen), end: at(HOURS.afterHoursClose) });
    });

    it('goes by the clock until the session is known', () => {
      expect(dayFrame(HOURS, null, morning, at('2026-10-14T12:00:00Z')).end).toBe(at(HOURS.regularClose));
      expect(dayFrame(HOURS, null, morning, at('2026-10-14T21:00:00Z')).end).toBe(at(HOURS.afterHoursClose));
    });
  });

  describe('placeByTime', () => {
    const domain = { start: 1000, end: 2000 };

    it('places points by time, leaving out those before the domain', () => {
      expect(placeByTime([500, 1000, 1500, 2000], domain, 200)).toEqual([null, 0, 100, 200]);
    });

    it('squeezes a lead-in into a band at the left, evenly spaced', () => {
      const lead = { ...domain, leadInFrom: 600 };
      const band = 200 * LEAD_IN_WIDTH;
      expect(placeByTime([500, 600, 800, 1000, 2000], lead, 200)).toEqual([null, 0, band / 2, band, 200]);
      expect(firstInDomain([500, 600, 800, 1000, 2000], lead)).toBe(3);
      expect(firstInDomain([500, 1000], domain)).toBe(0);
    });

    it('lets a newest point past the end take the end, rather than stand above the point there', () => {
      expect(placeByTime([1500, 2000, 2600], domain, 200)).toEqual([100, null, 200]);
    });
  });
});
