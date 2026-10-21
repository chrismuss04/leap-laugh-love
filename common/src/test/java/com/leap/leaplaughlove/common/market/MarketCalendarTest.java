package com.leap.leaplaughlove.common.market;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class MarketCalendarTest {

    private static Instant eastern(String localDateTime) {
        return LocalDateTime.parse(localDateTime).atZone(MarketCalendar.ZONE).toInstant();
    }

    @ParameterizedTest(name = "{0} is {1}")
    @CsvSource({
            // 2025, from the NYSE's published calendar
            "2025-01-01, New Year's Day",
            "2025-01-20, Martin Luther King Jr. Day",
            "2025-02-17, Washington's Birthday",
            "2025-04-18, Good Friday",
            "2025-05-26, Memorial Day",
            "2025-06-19, Juneteenth",
            "2025-07-04, Independence Day",
            "2025-09-01, Labor Day",
            "2025-11-27, Thanksgiving Day",
            "2025-12-25, Christmas Day",
            // 2026: Independence Day falls on a Saturday and is observed on Friday 3 July
            "2026-01-01, New Year's Day",
            "2026-01-19, Martin Luther King Jr. Day",
            "2026-02-16, Washington's Birthday",
            "2026-04-03, Good Friday",
            "2026-05-25, Memorial Day",
            "2026-06-19, Juneteenth",
            "2026-07-03, Independence Day",
            "2026-09-07, Labor Day",
            "2026-11-26, Thanksgiving Day",
            "2026-12-25, Christmas Day",
            // 2027: Juneteenth and Christmas fall on Saturdays
            "2027-01-01, New Year's Day",
            "2027-03-26, Good Friday",
            "2027-06-18, Juneteenth",
            "2027-07-05, Independence Day",
            "2027-12-24, Christmas Day",
            // 2023: New Year's Day on a Sunday is observed on Monday
            "2023-01-02, New Year's Day"
    })
    void knowsEachHoliday(LocalDate date, String holiday) {
        assertThat(MarketCalendar.holidayName(date)).isEqualTo(holiday);
        assertThat(MarketCalendar.isTradingDay(date)).isFalse();
        assertThat(MarketCalendar.tradingDay(date).isOpen()).isFalse();
    }

    @Test
    @DisplayName("New Year's Day on a Saturday isn't observed: 31 December trades")
    void saturdayNewYearIsNotObserved() {
        assertThat(MarketCalendar.isTradingDay(LocalDate.of(2027, 12, 31))).isTrue();
        assertThat(MarketCalendar.holidayName(LocalDate.of(2028, 1, 1))).isNull();
    }

    @Test
    @DisplayName("Juneteenth is only a holiday from 2022")
    void juneteenthStartsIn2022() {
        assertThat(MarketCalendar.isTradingDay(LocalDate.of(2021, 6, 18))).isTrue();
        assertThat(MarketCalendar.holidayName(LocalDate.of(2022, 6, 20))).isEqualTo("Juneteenth");
    }

    @Test
    void weekendsAreClosed() {
        assertThat(MarketCalendar.isTradingDay(LocalDate.of(2026, 10, 17))).isFalse();
        assertThat(MarketCalendar.isTradingDay(LocalDate.of(2026, 10, 18))).isFalse();
        assertThat(MarketCalendar.isTradingDay(LocalDate.of(2026, 10, 19))).isTrue();
    }

    @ParameterizedTest(name = "{0} closes early: {1}")
    @CsvSource({
            "2025-07-03, true",
            "2025-11-28, true",
            "2025-12-24, true",
            // 3 July 2026 is the observed Independence Day, a full holiday
            "2026-07-03, false",
            "2026-11-27, true",
            "2026-12-24, true",
            // Christmas Eve 2027 is the observed Christmas, a full holiday
            "2027-12-24, false",
            "2026-10-14, false"
    })
    void knowsEarlyCloses(LocalDate date, boolean early) {
        assertThat(MarketCalendar.isEarlyClose(date)).isEqualTo(early);
    }

    @Test
    void earlyCloseDayEndsRegularAt1300AndAfterHoursAt1700() {
        MarketCalendar.TradingDay day = MarketCalendar.tradingDay(LocalDate.of(2026, 11, 27));
        assertThat(day.earlyClose()).isTrue();
        assertThat(day.regularClose().toLocalTime()).hasToString("13:00");
        assertThat(day.afterHoursClose().toLocalTime()).hasToString("17:00");
        assertThat(MarketCalendar.sessionAt(eastern("2026-11-27T12:59:59"))).isEqualTo(MarketSession.REGULAR);
        assertThat(MarketCalendar.sessionAt(eastern("2026-11-27T13:00:00"))).isEqualTo(MarketSession.AFTER_HOURS);
        assertThat(MarketCalendar.sessionAt(eastern("2026-11-27T17:00:00"))).isEqualTo(MarketSession.CLOSED);
    }

    @ParameterizedTest(name = "{0} ET is {1}")
    @CsvSource({
            "2026-10-14T03:59:59, CLOSED",
            "2026-10-14T04:00:00, PRE_MARKET",
            "2026-10-14T09:29:59, PRE_MARKET",
            "2026-10-14T09:30:00, REGULAR",
            "2026-10-14T15:59:59, REGULAR",
            "2026-10-14T16:00:00, AFTER_HOURS",
            "2026-10-14T19:59:59, AFTER_HOURS",
            "2026-10-14T20:00:00, CLOSED",
            "2026-10-17T12:00:00, CLOSED",
            "2026-12-25T12:00:00, CLOSED"
    })
    void sessionBoundaries(String time, MarketSession session) {
        assertThat(MarketCalendar.sessionAt(eastern(time))).isEqualTo(session);
    }

    @Test
    @DisplayName("Sessions follow Eastern wall-clock time across the daylight-saving switch")
    void followsDaylightSaving() {
        // 9:30 ET is 13:30 UTC in summer and 14:30 UTC in winter.
        assertThat(MarketCalendar.sessionAt(Instant.parse("2026-10-30T13:30:00Z"))).isEqualTo(MarketSession.REGULAR);
        assertThat(MarketCalendar.sessionAt(Instant.parse("2026-11-02T13:30:00Z"))).isEqualTo(MarketSession.PRE_MARKET);
        assertThat(MarketCalendar.sessionAt(Instant.parse("2026-11-02T14:30:00Z"))).isEqualTo(MarketSession.REGULAR);
    }

    @Test
    @DisplayName("Today is measured from the previous trading day's regular close")
    void previousCloseDuringTheDay() {
        // Wednesday 14 October 2026: pre-market, regular and after-hours all count from Tuesday's close.
        assertThat(MarketCalendar.previousClose(eastern("2026-10-14T04:00:00"))).isEqualTo(eastern("2026-10-13T16:00:00"));
        assertThat(MarketCalendar.previousClose(eastern("2026-10-14T11:00:00"))).isEqualTo(eastern("2026-10-13T16:00:00"));
        assertThat(MarketCalendar.previousClose(eastern("2026-10-14T19:00:00"))).isEqualTo(eastern("2026-10-13T16:00:00"));
    }

    @Test
    @DisplayName("The day rolls over at the next pre-market open, not at midnight")
    void previousCloseRollsOverAtPreMarket() {
        assertThat(MarketCalendar.previousClose(eastern("2026-10-15T00:30:00"))).isEqualTo(eastern("2026-10-13T16:00:00"));
        assertThat(MarketCalendar.previousClose(eastern("2026-10-15T03:59:00"))).isEqualTo(eastern("2026-10-13T16:00:00"));
        assertThat(MarketCalendar.previousClose(eastern("2026-10-15T04:00:00"))).isEqualTo(eastern("2026-10-14T16:00:00"));
    }

    @Test
    @DisplayName("A weekend still shows Friday's move, and Monday is measured from Friday's close")
    void previousCloseAcrossAWeekend() {
        assertThat(MarketCalendar.previousClose(eastern("2026-10-17T12:00:00"))).isEqualTo(eastern("2026-10-15T16:00:00"));
        assertThat(MarketCalendar.previousClose(eastern("2026-10-19T10:00:00"))).isEqualTo(eastern("2026-10-16T16:00:00"));
    }

    @Test
    @DisplayName("A holiday is skipped, and an early close ends the day at 13:00")
    void previousCloseAcrossHolidaysAndEarlyCloses() {
        // Thanksgiving, Thursday 26 November 2026: Friday is measured from Wednesday's close.
        assertThat(MarketCalendar.previousClose(eastern("2026-11-27T10:00:00"))).isEqualTo(eastern("2026-11-25T16:00:00"));
        // Friday 27 November closes early, so Monday is measured from 13:00.
        assertThat(MarketCalendar.previousClose(eastern("2026-11-30T10:00:00"))).isEqualTo(eastern("2026-11-27T13:00:00"));
    }

    @Test
    void nextTransitionWithinADay() {
        assertThat(MarketCalendar.nextTransition(eastern("2026-10-14T10:00:00")))
                .isEqualTo(new MarketCalendar.Transition(eastern("2026-10-14T16:00:00"), MarketSession.AFTER_HOURS));
        assertThat(MarketCalendar.nextTransition(eastern("2026-10-14T09:30:00")))
                .isEqualTo(new MarketCalendar.Transition(eastern("2026-10-14T16:00:00"), MarketSession.AFTER_HOURS));
    }

    @Test
    @DisplayName("Friday evening's next transition is Monday's pre-market")
    void nextTransitionCrossesTheWeekend() {
        assertThat(MarketCalendar.nextTransition(eastern("2026-10-16T21:00:00")))
                .isEqualTo(new MarketCalendar.Transition(eastern("2026-10-19T04:00:00"), MarketSession.PRE_MARKET));
    }

    @Test
    @DisplayName("The next open skips weekends and holidays")
    void nextRegularOpenSkipsHolidays() {
        // Wednesday before Thanksgiving, after the close: Thursday is a holiday, so Friday.
        assertThat(MarketCalendar.nextRegularOpen(eastern("2026-11-25T17:00:00")))
                .isEqualTo(eastern("2026-11-27T09:30:00"));
        // Thursday 2 July 2026, after hours: Friday is the observed holiday, so Monday.
        assertThat(MarketCalendar.nextRegularOpen(eastern("2026-07-02T18:00:00")))
                .isEqualTo(eastern("2026-07-06T09:30:00"));
        // Pre-market: later the same day.
        assertThat(MarketCalendar.nextRegularOpen(eastern("2026-10-14T05:00:00")))
                .isEqualTo(eastern("2026-10-14T09:30:00"));
    }

    @Test
    void nextRegularOpenIsNowDuringRegularHours() {
        Instant now = eastern("2026-10-14T11:00:00");
        assertThat(MarketCalendar.nextRegularOpen(now)).isEqualTo(now);
    }

    @Test
    void nextTradingDay() {
        assertThat(MarketCalendar.nextTradingDay(LocalDate.of(2026, 12, 25)).date()).isEqualTo(LocalDate.of(2026, 12, 28));
        assertThat(MarketCalendar.nextTradingDay(LocalDate.of(2026, 10, 14)).date()).isEqualTo(LocalDate.of(2026, 10, 14));
    }

    @ParameterizedTest
    @CsvSource({"2024, 2024-03-31", "2025, 2025-04-20", "2026, 2026-04-05", "2027, 2027-03-28", "2038, 2038-04-25"})
    void easter(int year, LocalDate easter) {
        assertThat(MarketCalendar.easterSunday(year)).isEqualTo(easter);
    }
}
