package com.leap.leaplaughlove.common.market;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;

/**
 * Tells every service which market session is in force, from {@link MarketCalendar} and the
 * {@code market.hours.*} settings. common is outside each app's component scan, so an app that
 * needs it imports it, as with JwtService.
 *
 * <ul>
 *     <li>{@code market.hours.mode}: {@code REAL} (the default) follows the NYSE clock;
 *     {@code ALWAYS_OPEN} is always the regular session, for e2e and CI runs at any hour.</li>
 *     <li>{@code market.hours.force-session}: pins a session, so a demo can show any state at any
 *     hour. Ignored when always open.</li>
 *     <li>{@code market.hours.force-minutes}: how long a forced session lasts from startup before
 *     the regular session opens, e.g. to watch queued orders fill; 0 (the default) pins it for
 *     good. Each service counts from its own startup, so they may disagree for a few seconds.</li>
 * </ul>
 */
@Component
public class MarketClock {

    /** How the clock decides the session. */
    public enum Mode {
        /** Follows the NYSE calendar and clock. */
        REAL,
        /** Always the regular session, whatever the hour. */
        ALWAYS_OPEN
    }

    /**
     * The market's state at one instant.
     * @param now the instant it describes
     * @param session the session in force
     * @param next the next change of session, or null if there won't be one (always open, or pinned)
     * @param nextRegularOpen when regular trading next starts (now, if it is under way), or null
     *                        if it never will (a pinned session that isn't REGULAR)
     * @param today today's trading hours by the real calendar, US Eastern time
     * @param mode how the session was decided
     * @param forced whether the session is pinned by {@code market.hours.force-session}
     */
    public record Status(Instant now, MarketSession session, MarketCalendar.Transition next,
                         Instant nextRegularOpen, MarketCalendar.TradingDay today, Mode mode, boolean forced) {}

    /**
     * A session pinned for a demo.
     * @param session the pinned session
     * @param since when it was pinned: this clock's startup
     * @param until when it ends and the regular session opens, or null if it never does
     */
    public record Pin(MarketSession session, Instant since, Instant until) {}

    private final Clock clock;
    private final Mode mode;
    /** When this clock started, which is when a pinned session began. */
    private final Instant startedAt;
    private final MarketSession forcedSession;
    /** When a forced session ends and the regular session opens; null if it never does. */
    private final Instant forcedUntil;

    /**
     * Constructs a MarketClock from the application's settings.
     * @param mode REAL or ALWAYS_OPEN, case-insensitive
     * @param forceSession a session to pin, or blank for none
     * @param forceMinutes how long a pinned session lasts from now; 0 for good
     */
    @Autowired
    public MarketClock(@Value("${market.hours.mode:REAL}") String mode,
                       @Value("${market.hours.force-session:}") String forceSession,
                       @Value("${market.hours.force-minutes:0}") long forceMinutes) {
        this(Clock.systemUTC(), parseMode(mode), parseSession(forceSession), Duration.ofMinutes(forceMinutes));
    }

    /**
     * Constructs a MarketClock.
     * @param clock the clock to read the time from
     * @param mode how to decide the session
     * @param forcedSession a session to pin, or null for none
     * @param forceFor how long a pinned session lasts from now; zero or null for good
     */
    public MarketClock(Clock clock, Mode mode, MarketSession forcedSession, Duration forceFor) {
        this.clock = clock;
        this.startedAt = clock.instant();
        this.mode = mode != null ? mode : Mode.REAL;
        this.forcedSession = this.mode == Mode.ALWAYS_OPEN ? null : forcedSession;
        boolean expires = this.forcedSession != null && forceFor != null && !forceFor.isZero() && !forceFor.isNegative();
        this.forcedUntil = expires ? clock.instant().plus(forceFor) : null;
    }

    /**
     * A clock with market hours off: always the regular session. For code built without Spring,
     * e.g. in tests, and for callers that predate market hours.
     * @return an always-open clock on the system time
     */
    public static MarketClock alwaysOpen() {
        return new MarketClock(Clock.systemUTC(), Mode.ALWAYS_OPEN, null, null);
    }

    /**
     * Returns the current instant, by this clock.
     * @return now
     */
    public Instant now() {
        return clock.instant();
    }

    /**
     * Returns the session in force now.
     * @return the session
     */
    public MarketSession session() {
        return sessionAt(now());
    }

    /**
     * Returns the session in force at an instant, by this clock's settings.
     * @param instant the instant
     * @return the session
     */
    public MarketSession sessionAt(Instant instant) {
        if (mode == Mode.ALWAYS_OPEN) {
            return MarketSession.REGULAR;
        }
        if (forcedSession != null) {
            return forcedUntil == null || instant.isBefore(forcedUntil) ? forcedSession : MarketSession.REGULAR;
        }
        return MarketCalendar.sessionAt(instant);
    }

    /**
     * Returns when regular trading next starts: now, if it is under way.
     * @return the instant, or null if a pinned session means it never will
     */
    public Instant nextRegularOpen() {
        return nextRegularOpen(now());
    }

    private Instant nextRegularOpen(Instant now) {
        if (sessionAt(now) == MarketSession.REGULAR) {
            return now;
        }
        if (forcedSession != null) {
            return forcedUntil;
        }
        return MarketCalendar.nextRegularOpen(now);
    }

    /**
     * Returns the next change of session.
     * @return when it happens and to what, or null if the session won't change
     */
    public MarketCalendar.Transition nextTransition() {
        return nextTransition(now());
    }

    private MarketCalendar.Transition nextTransition(Instant now) {
        if (mode == Mode.ALWAYS_OPEN) {
            return null;
        }
        if (forcedSession != null) {
            return forcedUntil != null && now.isBefore(forcedUntil)
                    ? new MarketCalendar.Transition(forcedUntil, MarketSession.REGULAR)
                    : null;
        }
        return MarketCalendar.nextTransition(now);
    }

    /**
     * Returns the market's state now.
     * @return the session, what comes next and today's hours
     */
    public Status status() {
        Instant now = now();
        return new Status(now, sessionAt(now), nextTransition(now), nextRegularOpen(now),
                MarketCalendar.tradingDay(now.atZone(MarketCalendar.ZONE).toLocalDate()),
                mode, forcedSession != null);
    }

    /**
     * Returns how this clock decides the session.
     * @return the mode
     */
    public Mode mode() {
        return mode;
    }

    /**
     * Returns the session pinned for a demo, if there is one.
     * @return the pin, or null when the session follows the calendar (or market hours are off)
     */
    public Pin pin() {
        return forcedSession != null ? new Pin(forcedSession, startedAt, forcedUntil) : null;
    }

    private static Mode parseMode(String value) {
        if (value == null || value.isBlank()) {
            return Mode.REAL;
        }
        return Mode.valueOf(value.trim().toUpperCase(Locale.ROOT).replace('-', '_'));
    }

    private static MarketSession parseSession(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return MarketSession.valueOf(value.trim().toUpperCase(Locale.ROOT).replace('-', '_'));
    }
}
