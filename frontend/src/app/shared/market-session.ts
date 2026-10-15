/**
 * Market hours: the US equity market's sessions and how the app talks about them. Pure helpers,
 * shared by the header's market status pill and (later) the trade ticket.
 *
 * Every time is shown in US Eastern time, the exchange's own clock, whatever the viewer's zone.
 */

export type MarketSession = 'PRE_MARKET' | 'REGULAR' | 'AFTER_HOURS' | 'CLOSED';

/** One day's trading hours, as market-data-app sends them; the times are null on a closed day. */
export interface TradingDay {
  date: string;
  open: boolean;
  holiday: string | null;
  earlyClose: boolean;
  preMarketOpen: string | null;
  regularOpen: string | null;
  regularClose: string | null;
  afterHoursClose: string | null;
}

/** GET /api/marketdata/market/status. */
export interface MarketStatus {
  session: MarketSession;
  /** The server's clock when it answered, to correct for the browser's. */
  now: string;
  nextSession: MarketSession | null;
  nextTransitionAt: string | null;
  /** When regular trading next starts (now, if under way); null if it never will (a pinned demo session). */
  nextRegularOpen: string | null;
  today: TradingDay;
  mode: 'REAL' | 'ALWAYS_OPEN';
  /** Whether the session is pinned for a demo. */
  forced: boolean;
}

/** Colour family of a session, matching the theme tokens: gain, warning, info, muted. */
export type SessionTone = 'open' | 'pre' | 'after' | 'closed';

const LABELS: Record<MarketSession, string> = {
  PRE_MARKET: 'Pre-market',
  REGULAR: 'Market open',
  AFTER_HOURS: 'After-hours',
  CLOSED: 'Market closed'
};

const NAMES: Record<MarketSession, string> = {
  PRE_MARKET: 'Pre-market',
  REGULAR: 'Regular hours',
  AFTER_HOURS: 'After-hours',
  CLOSED: 'Closed'
};

const SHORT_LABELS: Record<MarketSession, string> = {
  PRE_MARKET: 'Pre',
  REGULAR: 'Open',
  AFTER_HOURS: 'After',
  CLOSED: 'Closed'
};

const TONES: Record<MarketSession, SessionTone> = {
  PRE_MARKET: 'pre',
  REGULAR: 'open',
  AFTER_HOURS: 'after',
  CLOSED: 'closed'
};

const MINUTE = 60_000;
const HOUR = 60 * MINUTE;

/** A countdown longer than this reads as a day and time instead ("Opens Mon 9:30 AM ET"). */
const COUNTDOWN_LIMIT = 12 * HOUR;

/** The width of the session timeline: pre-market's start to after-hours' end, 4:00-20:00 ET. */
const TRADING_DAY_SPAN = 16 * HOUR;

const EASTERN = 'America/New_York';
const TIME = new Intl.DateTimeFormat('en-US', { timeZone: EASTERN, hour: 'numeric', minute: '2-digit' });
const WEEKDAY = new Intl.DateTimeFormat('en-US', { timeZone: EASTERN, weekday: 'short' });
const LONG_DATE = new Intl.DateTimeFormat('en-US', { timeZone: EASTERN, weekday: 'short', month: 'short', day: 'numeric' });
const DATE_KEY = new Intl.DateTimeFormat('en-CA', { timeZone: EASTERN, year: 'numeric', month: '2-digit', day: '2-digit' });

/**
 * Whether an order on these terms executes in a session, as order-app decides (SessionPolicy):
 * everything in regular hours, only extended-hours limit orders in pre-market and after-hours, and
 * nothing while closed. A null session (status not loaded yet) counts as regular hours.
 */
export function canExecuteIn(session: MarketSession | null, orderType: string, extendedHours: boolean): boolean {
  switch (session ?? 'REGULAR') {
    case 'REGULAR': return true;
    case 'PRE_MARKET':
    case 'AFTER_HOURS': return orderType === 'LIMIT' && extendedHours;
    default: return false;
  }
}

/** Whether a session is pre-market or after-hours. */
export function isExtendedSession(session: MarketSession | null): boolean {
  return session === 'PRE_MARKET' || session === 'AFTER_HOURS';
}

/** "Market open", "Pre-market", "After-hours", "Market closed". */
export function sessionLabel(session: MarketSession): string {
  return LABELS[session];
}

/** "Pre-market", "Regular hours", "After-hours", "Closed": the session itself, e.g. on a timeline. */
export function sessionName(session: MarketSession): string {
  return NAMES[session];
}

/** "Open", "Pre", "After", "Closed": for narrow screens. */
export function sessionShortLabel(session: MarketSession): string {
  return SHORT_LABELS[session];
}

export function sessionTone(session: MarketSession): SessionTone {
  return TONES[session];
}

/** "2h 14m", "41m", "45s", "1d 3h": how long until something, rounded down. */
export function formatDuration(ms: number): string {
  const total = Math.max(0, ms);
  if (total < MINUTE) {
    return `${Math.floor(total / 1000)}s`;
  }
  const days = Math.floor(total / (24 * HOUR));
  const hours = Math.floor((total % (24 * HOUR)) / HOUR);
  const minutes = Math.floor((total % HOUR) / MINUTE);
  if (days > 0) {
    return hours > 0 ? `${days}d ${hours}h` : `${days}d`;
  }
  if (hours > 0) {
    return minutes > 0 ? `${hours}h ${minutes}m` : `${hours}h`;
  }
  return `${minutes}m`;
}

/** "9:30 AM": a time of day in Eastern time. */
export function easternTime(iso: string): string {
  return TIME.format(new Date(iso));
}

/** "9:30 AM ET" today, "Mon 9:30 AM ET" on another day (both in Eastern time). */
export function easternMoment(iso: string, nowMs: number): string {
  const at = new Date(iso);
  const sameDay = DATE_KEY.format(at) === DATE_KEY.format(new Date(nowMs));
  return `${sameDay ? '' : WEEKDAY.format(at) + ' '}${TIME.format(at)} ET`;
}

/** "Mon, Oct 19 · 9:30 AM ET". */
export function easternDateTime(iso: string): string {
  const at = new Date(iso);
  return `${LONG_DATE.format(at)} · ${TIME.format(at)} ET`;
}

/**
 * What happens next, for the header: "Closes in 2h 14m", "Opens in 41m", "Ends in 1h 5m",
 * "Opens Mon 9:30 AM ET". Null when the session won't change (always open, or a pinned demo).
 */
export function nextChangePhrase(status: MarketStatus, nowMs: number): string | null {
  if (status.mode === 'ALWAYS_OPEN') {
    return null;
  }
  switch (status.session) {
    case 'REGULAR':
      return status.nextTransitionAt ? `Closes in ${formatDuration(Date.parse(status.nextTransitionAt) - nowMs)}` : null;
    case 'AFTER_HOURS':
      // After-hours ends into the night; opening is next day's news.
      return status.nextTransitionAt ? `Ends in ${formatDuration(Date.parse(status.nextTransitionAt) - nowMs)}` : null;
    default:
      return status.nextRegularOpen ? opensPhrase(status.nextRegularOpen, nowMs) : null;
  }
}

function opensPhrase(openIso: string, nowMs: number): string {
  const until = Date.parse(openIso) - nowMs;
  return until <= COUNTDOWN_LIMIT ? `Opens in ${formatDuration(until)}` : `Opens ${easternMoment(openIso, nowMs)}`;
}

/** One stretch of the session timeline, as percentages of 4:00-20:00 ET. */
export interface TimelineSegment {
  session: MarketSession;
  /** Start, 0-100. */
  start: number;
  /** Width, 0-100. */
  width: number;
  /** "4:00 AM – 9:30 AM". */
  range: string;
}

/**
 * Today's sessions laid out from 4:00 to 20:00 ET. An early close shortens regular and
 * after-hours trading, leaving the end of the bar closed. Empty on a day the market is closed.
 */
export function timelineSegments(day: TradingDay): TimelineSegment[] {
  if (!day.open || !day.preMarketOpen || !day.regularOpen || !day.regularClose || !day.afterHoursClose) {
    return [];
  }
  const origin = Date.parse(day.preMarketOpen);
  const at = (iso: string) => (Date.parse(iso) - origin) / TRADING_DAY_SPAN * 100;
  const segment = (session: MarketSession, from: string, to: string): TimelineSegment => ({
    session,
    start: at(from),
    width: at(to) - at(from),
    range: `${easternTime(from)} – ${easternTime(to)}`
  });
  return [
    segment('PRE_MARKET', day.preMarketOpen, day.regularOpen),
    segment('REGULAR', day.regularOpen, day.regularClose),
    segment('AFTER_HOURS', day.regularClose, day.afterHoursClose)
  ];
}

/** Where now falls on today's timeline, 0-100; null outside 4:00-20:00 ET or on a closed day. */
export function timelinePosition(day: TradingDay, nowMs: number): number | null {
  if (!day.open || !day.preMarketOpen) {
    return null;
  }
  const position = (nowMs - Date.parse(day.preMarketOpen)) / TRADING_DAY_SPAN * 100;
  return position >= 0 && position <= 100 ? position : null;
}

/**
 * A line about today worth knowing, or null on an ordinary day: a holiday, the weekend, an early
 * close, a pinned demo session or market hours being off.
 */
export function dayNote(status: MarketStatus): string | null {
  if (status.mode === 'ALWAYS_OPEN') {
    return 'Market hours are off: trading is open around the clock.';
  }
  if (status.forced) {
    return 'Demo session: the market clock is pinned for testing.';
  }
  const today = status.today;
  if (today.holiday) {
    return `Closed today for ${today.holiday}.`;
  }
  if (!today.open) {
    return 'Closed for the weekend.';
  }
  if (today.earlyClose && today.regularClose) {
    return `Early close today: regular trading ends at ${easternTime(today.regularClose)} ET.`;
  }
  return null;
}
