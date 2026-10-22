package com.leap.leaplaughlove.common.market;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MarketClockTest {

    /** Saturday 17 October 2026, midday ET: closed. */
    private static final Instant SATURDAY = LocalDateTime.parse("2026-10-17T12:00:00")
            .atZone(MarketCalendar.ZONE).toInstant();

    /** A clock whose time a test can move. */
    private static final class MovableClock extends Clock {
        private final AtomicReference<Instant> now;

        MovableClock(Instant start) {
            now = new AtomicReference<>(start);
        }

        void advance(Duration by) {
            now.updateAndGet(instant -> instant.plus(by));
        }

        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now.get(); }
    }

    @Test
    void realModeFollowsTheCalendar() {
        MarketClock clock = new MarketClock(Clock.fixed(SATURDAY, ZoneOffset.UTC), MarketClock.Mode.REAL, null, null);

        MarketClock.Status status = clock.status();

        assertThat(status.session()).isEqualTo(MarketSession.CLOSED);
        assertThat(status.next().session()).isEqualTo(MarketSession.PRE_MARKET);
        assertThat(status.next().at()).isEqualTo(LocalDateTime.parse("2026-10-19T04:00:00").atZone(MarketCalendar.ZONE).toInstant());
        assertThat(status.nextRegularOpen()).isEqualTo(LocalDateTime.parse("2026-10-19T09:30:00").atZone(MarketCalendar.ZONE).toInstant());
        assertThat(status.today().isOpen()).isFalse();
        assertThat(status.forced()).isFalse();
    }

    @Test
    void alwaysOpenIsAlwaysRegular() {
        MarketClock clock = new MarketClock(Clock.fixed(SATURDAY, ZoneOffset.UTC), MarketClock.Mode.ALWAYS_OPEN,
                MarketSession.CLOSED, null);

        assertThat(clock.session()).isEqualTo(MarketSession.REGULAR);
        assertThat(clock.nextTransition()).isNull();
        assertThat(clock.nextRegularOpen()).isEqualTo(SATURDAY);
        // A forced session is ignored when always open.
        assertThat(clock.status().forced()).isFalse();
    }

    @Test
    void forcedSessionPinsTheSessionForGood() {
        MarketClock clock = new MarketClock(Clock.fixed(SATURDAY, ZoneOffset.UTC), MarketClock.Mode.REAL,
                MarketSession.AFTER_HOURS, Duration.ZERO);

        assertThat(clock.session()).isEqualTo(MarketSession.AFTER_HOURS);
        assertThat(clock.nextTransition()).isNull();
        assertThat(clock.nextRegularOpen()).isNull();
        assertThat(clock.status().forced()).isTrue();
    }

    @Test
    void forcedSessionOpensTheMarketWhenItRunsOut() {
        MovableClock time = new MovableClock(SATURDAY);
        MarketClock clock = new MarketClock(time, MarketClock.Mode.REAL, MarketSession.CLOSED, Duration.ofMinutes(2));

        assertThat(clock.session()).isEqualTo(MarketSession.CLOSED);
        assertThat(clock.nextTransition())
                .isEqualTo(new MarketCalendar.Transition(SATURDAY.plusSeconds(120), MarketSession.REGULAR));
        assertThat(clock.nextRegularOpen()).isEqualTo(SATURDAY.plusSeconds(120));

        time.advance(Duration.ofMinutes(2));

        assertThat(clock.session()).isEqualTo(MarketSession.REGULAR);
        assertThat(clock.nextTransition()).isNull();
        assertThat(clock.nextRegularOpen()).isEqualTo(time.instant());
    }

    @Test
    void parsesSettingsLeniently() {
        MarketClock clock = new MarketClock("always-open", "", 0);
        assertThat(clock.mode()).isEqualTo(MarketClock.Mode.ALWAYS_OPEN);

        MarketClock forced = new MarketClock("", "pre-market", 0);
        assertThat(forced.mode()).isEqualTo(MarketClock.Mode.REAL);
        assertThat(forced.session()).isEqualTo(MarketSession.PRE_MARKET);
    }

    @Test
    void reportsWhenAPinnedSessionBeganAndEnds() {
        MovableClock time = new MovableClock(SATURDAY);
        MarketClock pinned = new MarketClock(time, MarketClock.Mode.REAL, MarketSession.PRE_MARKET, Duration.ofMinutes(30));
        time.advance(Duration.ofMinutes(10));

        assertThat(pinned.pin()).isEqualTo(new MarketClock.Pin(MarketSession.PRE_MARKET, SATURDAY, SATURDAY.plusSeconds(1800)));
        assertThat(new MarketClock(time, MarketClock.Mode.REAL, MarketSession.CLOSED, null).pin().until()).isNull();
        assertThat(new MarketClock(time, MarketClock.Mode.REAL, null, null).pin()).isNull();
        assertThat(new MarketClock(time, MarketClock.Mode.ALWAYS_OPEN, MarketSession.PRE_MARKET, null).pin()).isNull();
    }

    @Test
    void rejectsUnknownSettings() {
        assertThatThrownBy(() -> new MarketClock("SOMETIMES", "", 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MarketClock("REAL", "LUNCH", 0)).isInstanceOf(IllegalArgumentException.class);
    }
}
