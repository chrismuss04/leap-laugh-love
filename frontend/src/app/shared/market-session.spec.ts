import {
  MarketStatus, TradingDay, canExecuteIn, dayNote, isExtendedSession, easternDateTime, easternMoment, formatDuration, nextChangePhrase, sessionLabel,
  sessionAt, sessionName, sessionRuns, sessionShortLabel, sessionsAt, sessionTone, timelinePosition, timelineSegments
} from './market-session';

/** Wednesday 14 October 2026 (EDT, UTC-4): 4:00, 9:30, 16:00 and 20:00 ET. */
const DAY: TradingDay = {
  date: '2026-10-14', open: true, holiday: null, earlyClose: false,
  preMarketOpen: '2026-10-14T08:00:00Z', regularOpen: '2026-10-14T13:30:00Z',
  regularClose: '2026-10-14T20:00:00Z', afterHoursClose: '2026-10-15T00:00:00Z'
};

const CLOSED_DAY: TradingDay = {
  date: '2026-10-17', open: false, holiday: null, earlyClose: false,
  preMarketOpen: null, regularOpen: null, regularClose: null, afterHoursClose: null
};

function status(overrides: Partial<MarketStatus>): MarketStatus {
  return {
    session: 'REGULAR', now: '2026-10-14T14:00:00Z', nextSession: 'AFTER_HOURS',
    nextTransitionAt: '2026-10-14T20:00:00Z', nextRegularOpen: '2026-10-14T14:00:00Z',
    today: DAY, mode: 'REAL', forced: false, ...overrides
  };
}

const at = (iso: string) => Date.parse(iso);

describe('market session helpers', () => {
  it('knows which orders execute in which session, as order-app does', () => {
    expect(canExecuteIn('REGULAR', 'STOP', false)).toBe(true);
    expect(canExecuteIn('PRE_MARKET', 'LIMIT', true)).toBe(true);
    expect(canExecuteIn('AFTER_HOURS', 'LIMIT', false)).toBe(false);
    expect(canExecuteIn('AFTER_HOURS', 'MARKET', false)).toBe(false);
    expect(canExecuteIn('CLOSED', 'LIMIT', true)).toBe(false);
    expect(canExecuteIn(null, 'MARKET', false)).toBe(true);
    expect(isExtendedSession('PRE_MARKET')).toBe(true);
    expect(isExtendedSession('CLOSED')).toBe(false);
  });

  it('splits points into runs of the same session', () => {
    expect(sessionRuns([])).toEqual([]);
    expect(sessionRuns(['REGULAR', 'AFTER_HOURS', 'AFTER_HOURS', 'PRE_MARKET', undefined, null])).toEqual([
      { session: 'REGULAR', from: 0, to: 0 },
      { session: 'AFTER_HOURS', from: 1, to: 2 },
      { session: 'PRE_MARKET', from: 3, to: 3 },
      { session: 'REGULAR', from: 4, to: 5 }
    ]);
  });

  describe('sessionAt', () => {
    it('places other days on the usual hours, Eastern time', () => {
      const s = status({});
      // Tuesday 13 October 2026, EDT.
      expect(sessionAt(at('2026-10-13T07:55:00Z'), s)).toBe('CLOSED');
      expect(sessionAt(at('2026-10-13T08:00:00Z'), s)).toBe('PRE_MARKET');
      expect(sessionAt(at('2026-10-13T13:25:00Z'), s)).toBe('PRE_MARKET');
      expect(sessionAt(at('2026-10-13T13:30:00Z'), s)).toBe('REGULAR');
      expect(sessionAt(at('2026-10-13T20:00:00Z'), s)).toBe('AFTER_HOURS');
      expect(sessionAt(at('2026-10-14T00:00:00Z'), s)).toBe('CLOSED');
      expect(sessionAt(at('2026-10-17T15:00:00Z'), s)).toBe('CLOSED');
    });

    it('uses today\'s own hours for today, so an early close is right', () => {
      const early = status({ today: { ...DAY, earlyClose: true, regularClose: '2026-10-14T17:00:00Z',
        afterHoursClose: '2026-10-14T21:00:00Z' } });
      expect(sessionAt(at('2026-10-14T16:55:00Z'), early)).toBe('REGULAR');
      expect(sessionAt(at('2026-10-14T17:00:00Z'), early)).toBe('AFTER_HOURS');
      expect(sessionAt(at('2026-10-14T21:00:00Z'), early)).toBe('CLOSED');
    });

    it('is always regular hours when market hours are off, and unknown until the status loads', () => {
      expect(sessionAt(at('2026-10-17T03:00:00Z'), status({ mode: 'ALWAYS_OPEN' }))).toBe('REGULAR');
      expect(sessionsAt([at('2026-10-13T14:00:00Z'), at('2026-10-13T21:00:00Z')], status({})))
        .toEqual(['REGULAR', 'AFTER_HOURS']);
      expect(sessionsAt([at('2026-10-13T14:00:00Z')], null)).toBeNull();
    });
  });

  it('labels and colours each session', () => {
    expect(sessionLabel('REGULAR')).toBe('Market open');
    expect(sessionLabel('CLOSED')).toBe('Market closed');
    expect(sessionName('REGULAR')).toBe('Regular hours');
    expect(sessionShortLabel('AFTER_HOURS')).toBe('After');
    expect(sessionTone('PRE_MARKET')).toBe('pre');
    expect(sessionTone('AFTER_HOURS')).toBe('after');
  });

  it('formats durations, rounding down', () => {
    expect(formatDuration(45_500)).toBe('45s');
    expect(formatDuration(41 * 60_000 + 59_000)).toBe('41m');
    expect(formatDuration(2 * 3_600_000 + 14 * 60_000)).toBe('2h 14m');
    expect(formatDuration(3 * 3_600_000)).toBe('3h');
    expect(formatDuration(27 * 3_600_000)).toBe('1d 3h');
    expect(formatDuration(-5)).toBe('0s');
  });

  it('shows times in Eastern time, with the weekday when it is another day', () => {
    const now = at('2026-10-14T14:00:00Z');
    expect(easternMoment('2026-10-14T20:00:00Z', now)).toBe('4:00 PM ET');
    expect(easternMoment('2026-10-19T13:30:00Z', now)).toBe('Mon 9:30 AM ET');
    expect(easternDateTime('2026-10-19T13:30:00Z')).toBe('Mon, Oct 19 · 9:30 AM ET');
  });

  describe('nextChangePhrase', () => {
    it('counts down to the close during regular hours', () => {
      expect(nextChangePhrase(status({}), at('2026-10-14T17:46:00Z'))).toBe('Closes in 2h 14m');
    });

    it('counts down to the open in pre-market', () => {
      const pre = status({ session: 'PRE_MARKET', nextSession: 'REGULAR', nextTransitionAt: '2026-10-14T13:30:00Z',
        nextRegularOpen: '2026-10-14T13:30:00Z' });
      expect(nextChangePhrase(pre, at('2026-10-14T12:49:00Z'))).toBe('Opens in 41m');
    });

    it('counts down to the end of after-hours', () => {
      const after = status({ session: 'AFTER_HOURS', nextSession: 'CLOSED', nextTransitionAt: '2026-10-15T00:00:00Z',
        nextRegularOpen: '2026-10-15T13:30:00Z' });
      expect(nextChangePhrase(after, at('2026-10-14T22:55:00Z'))).toBe('Ends in 1h 5m');
    });

    it('names the day of an open more than 12 hours away', () => {
      const weekend = status({ session: 'CLOSED', nextSession: 'PRE_MARKET', nextTransitionAt: '2026-10-19T08:00:00Z',
        nextRegularOpen: '2026-10-19T13:30:00Z', today: CLOSED_DAY });
      expect(nextChangePhrase(weekend, at('2026-10-17T16:00:00Z'))).toBe('Opens Mon 9:30 AM ET');
    });

    it('says nothing when the session will not change', () => {
      expect(nextChangePhrase(status({ mode: 'ALWAYS_OPEN' }), at('2026-10-14T14:00:00Z'))).toBeNull();
      const pinned = status({ session: 'CLOSED', forced: true, nextSession: null, nextTransitionAt: null, nextRegularOpen: null });
      expect(nextChangePhrase(pinned, at('2026-10-14T14:00:00Z'))).toBeNull();
    });
  });

  describe('timeline', () => {
    it('lays out the three sessions across 4:00-20:00 ET', () => {
      const segments = timelineSegments(DAY);
      expect(segments.map(s => s.session)).toEqual(['PRE_MARKET', 'REGULAR', 'AFTER_HOURS']);
      expect(segments[0].start).toBe(0);
      expect(segments[0].width).toBeCloseTo(34.375);
      expect(segments[1].width).toBeCloseTo(40.625);
      expect(segments[2].start + segments[2].width).toBeCloseTo(100);
      expect(segments[1].range).toBe('9:30 AM – 4:00 PM');
    });

    it('shortens the day on an early close', () => {
      const early: TradingDay = { ...DAY, earlyClose: true, regularClose: '2026-10-14T17:00:00Z', afterHoursClose: '2026-10-14T21:00:00Z' };
      const segments = timelineSegments(early);
      expect(segments[2].start + segments[2].width).toBeCloseTo(81.25);
    });

    it('is empty on a closed day', () => {
      expect(timelineSegments(CLOSED_DAY)).toEqual([]);
      expect(timelinePosition(CLOSED_DAY, at('2026-10-17T14:00:00Z'))).toBeNull();
    });

    it('places now on the day, or nowhere outside it', () => {
      expect(timelinePosition(DAY, at('2026-10-14T16:00:00Z'))).toBeCloseTo(50);
      expect(timelinePosition(DAY, at('2026-10-14T07:00:00Z'))).toBeNull();
      expect(timelinePosition(DAY, at('2026-10-15T01:00:00Z'))).toBeNull();
    });
  });

  it('notes holidays, weekends, early closes and overrides', () => {
    expect(dayNote(status({}))).toBeNull();
    expect(dayNote(status({ today: { ...CLOSED_DAY, holiday: 'Thanksgiving Day' } }))).toBe('Closed today for Thanksgiving Day.');
    expect(dayNote(status({ today: CLOSED_DAY }))).toBe('Closed for the weekend.');
    expect(dayNote(status({ today: { ...DAY, earlyClose: true, regularClose: '2026-10-14T17:00:00Z' } })))
      .toBe('Early close today: regular trading ends at 1:00 PM ET.');
    expect(dayNote(status({ forced: true }))).toContain('Demo session');
    expect(dayNote(status({ mode: 'ALWAYS_OPEN' }))).toContain('around the clock');
  });
});
