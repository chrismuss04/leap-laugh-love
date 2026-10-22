import { MarketSession } from './market-session';

/**
 * Laying a day's prices out by time, shared by the portfolio chart and the stock sparklines so
 * both frame the day the same way.
 */

/** One trading day's sessions, as ISO instants. */
export interface TradingHours {
  preMarketOpen: string;
  regularOpen: string;
  regularClose: string;
  afterHoursClose: string;
}

/**
 * The stretch of time a chart spans, in epoch ms: points before it aren't drawn, and time after the
 * last point is left empty.
 */
export interface ChartDomain {
  start: number;
  end: number;
  /**
   * Points from this time up to `start` are drawn squeezed into a lead-in at the left, leading
   * into the day from where the previous session left off, as Robinhood does in pre-market.
   */
  leadInFrom?: number | null;
}

/** How much of the width a lead-in takes: a tenth, or a fifth on a sparkline, where a tenth is a few pixels. */
export const LEAD_IN_WIDTH = 0.1;
export const SPARKLINE_LEAD_IN_WIDTH = 0.2;
/** How many prices lead into the day: the last hour, at 5 minutes. */
export const LEAD_IN_POINTS = 12;

/**
 * Where today's chart runs, framed by where the day is: in pre-market from the pre-market open to
 * the regular close, so the morning sits at the start with room for the day ahead, led into by the
 * previous session's last hour; in regular hours, the regular session; after the close (and
 * overnight, and over a weekend) from the open to the end of after-hours. A day that has already
 * traded past where the session says it is - a session pinned for a demo can say pre-market over a
 * finished day - is framed as far as it got.
 * @param hours the day's sessions
 * @param session the session under way, or null until it's known (the clock decides then)
 * @param traded when each price was made, oldest first, not counting a baseline or a live value
 * @param now the time now
 */
export function dayFrame(hours: TradingHours, session: MarketSession | null, traded: number[], now: number): ChartDomain {
  const [preMarketOpen, regularOpen, regularClose, afterHoursClose] =
    [hours.preMarketOpen, hours.regularOpen, hours.regularClose, hours.afterHoursClose].map(Date.parse);
  const reached = (moment: number) => traded.some(time => time > moment);
  if ((session === 'PRE_MARKET' || (!session && now < regularOpen)) && !reached(regularOpen)) {
    return { start: preMarketOpen, end: regularClose, leadInFrom: leadInStart(traded, preMarketOpen) };
  }
  if ((session === 'REGULAR' || (!session && now < regularClose)) && !reached(regularClose)) {
    return { start: regularOpen, end: regularClose };
  }
  return { start: regularOpen, end: afterHoursClose };
}

/** When the lead-in into a day starting at `start` begins: the last few prices before it, if any. */
function leadInStart(times: number[], start: number): number | null {
  const before = times.filter(time => time < start).length;
  return before ? times[Math.max(0, before - LEAD_IN_POINTS)] : null;
}

/**
 * Each point's x across a width: by time across the domain, with any lead-in spaced evenly in the
 * band at the left. Points before the domain (and its lead-in) aren't drawn. The newest point past
 * its end - the live value while the market is closed - takes the end in place of the point there,
 * rather than standing straight above it.
 */
export function placeByTime(times: number[], domain: ChartDomain, width: number,
                            leadInWidth = LEAD_IN_WIDTH): (number | null)[] {
  const leadInFrom = domain.leadInFrom;
  const lead = leadInFrom != null ? times.flatMap((time, i) => time >= leadInFrom && time < domain.start ? [i] : []) : [];
  const leadWidth = lead.length ? width * leadInWidth : 0;
  const span = domain.end - domain.start || 1;
  const last = times.length - 1;
  const pastEnd = times[last] > domain.end;
  return times.map((time, i) => {
    const k = lead.indexOf(i);
    if (k >= 0) {
      return (k / lead.length) * leadWidth;
    }
    if (time < domain.start || (pastEnd && i < last && time >= domain.end)) {
      return null;
    }
    return leadWidth + Math.min(1, (time - domain.start) / span) * (width - leadWidth);
  });
}

/** The first point inside the domain proper, after any lead-in: the lead-in is never highlighted. */
export function firstInDomain(times: number[], domain: ChartDomain | null): number {
  if (!domain?.leadInFrom) {
    return 0;
  }
  const index = times.findIndex(time => time >= domain.start);
  return index < 0 ? times.length : index;
}
