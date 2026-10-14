package com.leap.leaplaughlove.marketdata.api;

import com.leap.leaplaughlove.common.market.MarketCalendar;
import com.leap.leaplaughlove.common.market.MarketClock;
import com.leap.leaplaughlove.common.market.MarketSession;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;

/**
 * The market's session now, what comes next, and today's hours. Times are UTC instants; the
 * frontend shows them in US Eastern time.
 *
 * @param session the session in force: PRE_MARKET, REGULAR, AFTER_HOURS or CLOSED
 * @param now when this was worked out, so the client can correct for its own clock
 * @param nextSession the session that starts next, or null if it won't change
 * @param nextTransitionAt when it starts, or null
 * @param nextRegularOpen when regular trading next starts (now, if it is under way), or null if never
 * @param today today's trading hours
 * @param mode REAL or ALWAYS_OPEN
 * @param forced whether the session is pinned for a demo
 */
public record MarketStatusResponse(
        MarketSession session,
        OffsetDateTime now,
        MarketSession nextSession,
        OffsetDateTime nextTransitionAt,
        OffsetDateTime nextRegularOpen,
        TradingDayResponse today,
        MarketClock.Mode mode,
        boolean forced) {

    /**
     * One day's trading hours; the times are null when the market is closed all day.
     *
     * @param date the date, in US Eastern time
     * @param open whether the market trades that day
     * @param holiday the holiday it is closed for, or null
     * @param earlyClose whether regular trading ends at 13:00
     * @param preMarketOpen when pre-market starts
     * @param regularOpen when regular trading starts
     * @param regularClose when regular trading ends
     * @param afterHoursClose when after-hours trading ends
     */
    public record TradingDayResponse(
            LocalDate date,
            boolean open,
            String holiday,
            boolean earlyClose,
            OffsetDateTime preMarketOpen,
            OffsetDateTime regularOpen,
            OffsetDateTime regularClose,
            OffsetDateTime afterHoursClose) {

        static TradingDayResponse of(MarketCalendar.TradingDay day) {
            return new TradingDayResponse(day.date(), day.isOpen(), day.holiday(), day.earlyClose(),
                    utc(day.preMarketOpen()), utc(day.regularOpen()), utc(day.regularClose()),
                    utc(day.afterHoursClose()));
        }
    }

    /**
     * Builds the response from the clock's status.
     * @param status the market's state
     * @return the response
     */
    public static MarketStatusResponse of(MarketClock.Status status) {
        return new MarketStatusResponse(
                status.session(),
                utc(status.now()),
                status.next() != null ? status.next().session() : null,
                status.next() != null ? utc(status.next().at()) : null,
                utc(status.nextRegularOpen()),
                TradingDayResponse.of(status.today()),
                status.mode(),
                status.forced());
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant != null ? instant.atOffset(ZoneOffset.UTC) : null;
    }

    private static OffsetDateTime utc(ZonedDateTime time) {
        return time != null ? utc(time.toInstant()) : null;
    }
}
