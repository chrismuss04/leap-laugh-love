package com.leap.leaplaughlove.common.market;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.Month;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;

/**
 * The NYSE trading calendar, worked out from the exchange's rules rather than a list of dates,
 * so it never runs out. Pure functions with no state.
 *
 * <p>Holidays: New Year's Day, Martin Luther King Jr. Day, Washington's Birthday, Good Friday,
 * Memorial Day, Juneteenth (from 2022), Independence Day, Labor Day, Thanksgiving and Christmas.
 * A holiday on a Saturday is observed the Friday before and one on a Sunday the Monday after -
 * except New Year's Day on a Saturday, which isn't observed (the exchange stays open on 31 December).
 *
 * <p>Early closes, at 13:00 with after-hours until 17:00: 3 July, the day after Thanksgiving and
 * Christmas Eve, whenever each is a trading day.
 *
 * <p>Unscheduled closures (e.g. national days of mourning) are not modelled.
 */
public final class MarketCalendar {

    /** The exchange's time zone; every session boundary is wall-clock time here. */
    public static final ZoneId ZONE = ZoneId.of("America/New_York");

    static final LocalTime PRE_MARKET_OPEN = LocalTime.of(4, 0);
    static final LocalTime REGULAR_OPEN = LocalTime.of(9, 30);
    static final LocalTime REGULAR_CLOSE = LocalTime.of(16, 0);
    static final LocalTime AFTER_HOURS_CLOSE = LocalTime.of(20, 0);
    static final LocalTime EARLY_REGULAR_CLOSE = LocalTime.of(13, 0);
    static final LocalTime EARLY_AFTER_HOURS_CLOSE = LocalTime.of(17, 0);

    /** Far enough to cross any run of weekends and holidays. */
    private static final int MAX_DAYS_AHEAD = 10;

    /**
     * One calendar day's sessions. Times are null on a day the market is closed.
     * @param date the date, in US Eastern time
     * @param holiday the holiday the market is closed for, or null
     * @param earlyClose whether regular trading ends at 13:00
     * @param preMarketOpen when pre-market starts
     * @param regularOpen when regular trading starts
     * @param regularClose when regular trading ends
     * @param afterHoursClose when after-hours trading ends
     */
    public record TradingDay(LocalDate date, String holiday, boolean earlyClose,
                             ZonedDateTime preMarketOpen, ZonedDateTime regularOpen,
                             ZonedDateTime regularClose, ZonedDateTime afterHoursClose) {
        /**
         * Returns whether the market trades on this day.
         * @return false at weekends and on holidays
         */
        public boolean isOpen() {
            return regularOpen != null;
        }
    }

    /**
     * The next change of session.
     * @param at when it happens
     * @param session the session that starts then
     */
    public record Transition(Instant at, MarketSession session) {}

    private MarketCalendar() {
    }

    /**
     * Returns the sessions of one day.
     * @param date the date, in US Eastern time
     * @return its trading hours, or a closed day
     */
    public static TradingDay tradingDay(LocalDate date) {
        String holiday = holidayName(date);
        if (holiday != null || isWeekend(date)) {
            return new TradingDay(date, holiday, false, null, null, null, null);
        }
        boolean early = isEarlyClose(date);
        return new TradingDay(date, null, early,
                date.atTime(PRE_MARKET_OPEN).atZone(ZONE),
                date.atTime(REGULAR_OPEN).atZone(ZONE),
                date.atTime(early ? EARLY_REGULAR_CLOSE : REGULAR_CLOSE).atZone(ZONE),
                date.atTime(early ? EARLY_AFTER_HOURS_CLOSE : AFTER_HOURS_CLOSE).atZone(ZONE));
    }

    /**
     * Returns whether the market trades on a date.
     * @param date the date, in US Eastern time
     * @return false at weekends and on holidays
     */
    public static boolean isTradingDay(LocalDate date) {
        return !isWeekend(date) && holidayName(date) == null;
    }

    /**
     * Returns the session in force at an instant.
     * @param instant the instant
     * @return the session
     */
    public static MarketSession sessionAt(Instant instant) {
        ZonedDateTime time = instant.atZone(ZONE);
        TradingDay day = tradingDay(time.toLocalDate());
        if (!day.isOpen() || time.isBefore(day.preMarketOpen()) || !time.isBefore(day.afterHoursClose())) {
            return MarketSession.CLOSED;
        }
        if (time.isBefore(day.regularOpen())) {
            return MarketSession.PRE_MARKET;
        }
        return time.isBefore(day.regularClose()) ? MarketSession.REGULAR : MarketSession.AFTER_HOURS;
    }

    /**
     * Returns the next change of session after an instant.
     * @param instant the instant
     * @return when the session next changes, and to what
     */
    public static Transition nextTransition(Instant instant) {
        LocalDate date = instant.atZone(ZONE).toLocalDate();
        for (int i = 0; i <= MAX_DAYS_AHEAD; i++) {
            TradingDay day = tradingDay(date.plusDays(i));
            if (!day.isOpen()) {
                continue;
            }
            Transition[] boundaries = {
                    new Transition(day.preMarketOpen().toInstant(), MarketSession.PRE_MARKET),
                    new Transition(day.regularOpen().toInstant(), MarketSession.REGULAR),
                    new Transition(day.regularClose().toInstant(), MarketSession.AFTER_HOURS),
                    new Transition(day.afterHoursClose().toInstant(), MarketSession.CLOSED)
            };
            for (Transition boundary : boundaries) {
                if (boundary.at().isAfter(instant)) {
                    return boundary;
                }
            }
        }
        throw new IllegalStateException("No trading day within " + MAX_DAYS_AHEAD + " days of " + instant);
    }

    /**
     * Returns when regular trading next starts: now, if it is under way, or else the next open.
     * @param instant the instant
     * @return the start of the regular session in force or next to come
     */
    public static Instant nextRegularOpen(Instant instant) {
        if (sessionAt(instant) == MarketSession.REGULAR) {
            return instant;
        }
        LocalDate date = instant.atZone(ZONE).toLocalDate();
        for (int i = 0; i <= MAX_DAYS_AHEAD; i++) {
            TradingDay day = tradingDay(date.plusDays(i));
            if (day.isOpen() && day.regularOpen().toInstant().isAfter(instant)) {
                return day.regularOpen().toInstant();
            }
        }
        throw new IllegalStateException("No trading day within " + MAX_DAYS_AHEAD + " days of " + instant);
    }

    /**
     * Returns the regular close that today's change is measured from: the close of the trading day
     * before the one under way. A trading day is under way from its pre-market open until the next
     * one's, so its after-hours, the night after and a weekend or holiday that follows all still
     * count toward it - on a Saturday, "today" is Friday's move from Thursday's close.
     * @param instant the instant
     * @return when the previous trading day's regular session ended
     */
    public static Instant previousClose(Instant instant) {
        LocalDate date = instant.atZone(ZONE).toLocalDate();
        for (int i = 0; i <= MAX_DAYS_AHEAD; i++) {
            TradingDay day = tradingDay(date.minusDays(i));
            if (day.isOpen() && !day.preMarketOpen().toInstant().isAfter(instant)) {
                return previousTradingDay(day.date()).regularClose().toInstant();
            }
        }
        throw new IllegalStateException("No trading day within " + MAX_DAYS_AHEAD + " days before " + instant);
    }

    /**
     * Returns the last trading day before a date.
     * @param date the date to look back from
     * @return the first trading day before it
     */
    public static TradingDay previousTradingDay(LocalDate date) {
        for (int i = 1; i <= MAX_DAYS_AHEAD; i++) {
            TradingDay day = tradingDay(date.minusDays(i));
            if (day.isOpen()) {
                return day;
            }
        }
        throw new IllegalStateException("No trading day within " + MAX_DAYS_AHEAD + " days before " + date);
    }

    /**
     * Returns the next trading day on or after a date.
     * @param date the date to start from
     * @return the first trading day from it
     */
    public static TradingDay nextTradingDay(LocalDate date) {
        for (int i = 0; i <= MAX_DAYS_AHEAD; i++) {
            TradingDay day = tradingDay(date.plusDays(i));
            if (day.isOpen()) {
                return day;
            }
        }
        throw new IllegalStateException("No trading day within " + MAX_DAYS_AHEAD + " days of " + date);
    }

    /**
     * Returns the holiday the market is closed for on a date.
     * @param date the date
     * @return the holiday's name, or null if the date isn't an exchange holiday
     */
    public static String holidayName(LocalDate date) {
        int year = date.getYear();
        // New Year's Day on a Sunday is observed on 2 January; on a Saturday it isn't observed.
        LocalDate newYear = LocalDate.of(year, Month.JANUARY, 1);
        if (date.equals(newYear.getDayOfWeek() == DayOfWeek.SUNDAY ? newYear.plusDays(1) : newYear)
                && !isWeekend(date)) {
            return "New Year's Day";
        }
        if (date.equals(nthWeekday(year, Month.JANUARY, DayOfWeek.MONDAY, 3))) {
            return "Martin Luther King Jr. Day";
        }
        if (date.equals(nthWeekday(year, Month.FEBRUARY, DayOfWeek.MONDAY, 3))) {
            return "Washington's Birthday";
        }
        if (date.equals(easterSunday(year).minusDays(2))) {
            return "Good Friday";
        }
        if (date.equals(LocalDate.of(year, Month.MAY, 31).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)))) {
            return "Memorial Day";
        }
        if (year >= 2022 && date.equals(observed(LocalDate.of(year, Month.JUNE, 19)))) {
            return "Juneteenth";
        }
        if (date.equals(observed(LocalDate.of(year, Month.JULY, 4)))) {
            return "Independence Day";
        }
        if (date.equals(nthWeekday(year, Month.SEPTEMBER, DayOfWeek.MONDAY, 1))) {
            return "Labor Day";
        }
        if (date.equals(thanksgiving(year))) {
            return "Thanksgiving Day";
        }
        if (date.equals(observed(LocalDate.of(year, Month.DECEMBER, 25)))) {
            return "Christmas Day";
        }
        return null;
    }

    /**
     * Returns whether regular trading ends at 13:00 on a date.
     * @param date the date
     * @return true on 3 July, the day after Thanksgiving and Christmas Eve, when each is a trading day
     */
    public static boolean isEarlyClose(LocalDate date) {
        if (isWeekend(date) || holidayName(date) != null) {
            return false;
        }
        int year = date.getYear();
        return date.equals(LocalDate.of(year, Month.JULY, 3))
                || date.equals(thanksgiving(year).plusDays(1))
                || date.equals(LocalDate.of(year, Month.DECEMBER, 24));
    }

    private static boolean isWeekend(LocalDate date) {
        DayOfWeek day = date.getDayOfWeek();
        return day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY;
    }

    /** A fixed-date holiday moved off the weekend: Saturday to Friday, Sunday to Monday. */
    private static LocalDate observed(LocalDate holiday) {
        return switch (holiday.getDayOfWeek()) {
            case SATURDAY -> holiday.minusDays(1);
            case SUNDAY -> holiday.plusDays(1);
            default -> holiday;
        };
    }

    private static LocalDate nthWeekday(int year, Month month, DayOfWeek weekday, int n) {
        return LocalDate.of(year, month, 1).with(TemporalAdjusters.dayOfWeekInMonth(n, weekday));
    }

    private static LocalDate thanksgiving(int year) {
        return nthWeekday(year, Month.NOVEMBER, DayOfWeek.THURSDAY, 4);
    }

    /** Easter Sunday in the Gregorian calendar (the anonymous Gregorian algorithm). */
    static LocalDate easterSunday(int year) {
        int a = year % 19;
        int b = year / 100;
        int c = year % 100;
        int d = b / 4;
        int e = b % 4;
        int f = (b + 8) / 25;
        int g = (b - f + 1) / 3;
        int h = (19 * a + b - d - g + 15) % 30;
        int i = c / 4;
        int k = c % 4;
        int l = (32 + 2 * e + 2 * i - h - k) % 7;
        int m = (a + 11 * h + 22 * l) / 451;
        int month = (h + l - 7 * m + 114) / 31;
        int day = ((h + l - 7 * m + 114) % 31) + 1;
        return LocalDate.of(year, month, day);
    }
}
