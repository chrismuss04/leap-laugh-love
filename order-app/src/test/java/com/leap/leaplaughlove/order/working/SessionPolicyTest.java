package com.leap.leaplaughlove.order.working;

import com.leap.leaplaughlove.common.market.MarketCalendar;
import com.leap.leaplaughlove.common.market.MarketClock;
import com.leap.leaplaughlove.common.market.MarketSession;
import com.leap.leaplaughlove.order.order.Order;
import com.leap.leaplaughlove.order.submission.OrderSubmissionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class SessionPolicyTest {

    private static OffsetDateTime eastern(String time) {
        return LocalDateTime.parse(time).atZone(MarketCalendar.ZONE).toOffsetDateTime();
    }

    @ParameterizedTest(name = "{0} (extended hours {1}) in {2}: {3}")
    @CsvSource({
            "MARKET,        false, REGULAR,     true",
            "LIMIT,         false, REGULAR,     true",
            "STOP,          false, REGULAR,     true",
            "TRAILING_STOP, false, REGULAR,     true",
            "LIMIT,         true,  PRE_MARKET,  true",
            "LIMIT,         true,  AFTER_HOURS, true",
            "LIMIT,         false, PRE_MARKET,  false",
            "LIMIT,         false, AFTER_HOURS, false",
            "STOP,          false, AFTER_HOURS, false",
            "STOP_LIMIT,    false, PRE_MARKET,  false",
            "TRAILING_STOP, false, AFTER_HOURS, false",
            "MARKET,        false, PRE_MARKET,  false",
            "LIMIT,         true,  CLOSED,      false",
            "MARKET,        false, CLOSED,      false"
    })
    void whoMayExecuteWhen(Order.OrderType type, boolean extendedHours, MarketSession session, boolean allowed) {
        assertThat(SessionPolicy.canExecute(type, extendedHours, session)).isEqualTo(allowed);
    }

    @ParameterizedTest(name = "placed {0} ET, extended hours {1}: expires {2} ET")
    @CsvSource({
            // Before the close: good for today's regular session.
            "2026-10-14T07:00:00, false, 2026-10-14T16:00",
            "2026-10-14T11:00:00, false, 2026-10-14T16:00",
            // After the close: good for the next trading day.
            "2026-10-14T16:30:00, false, 2026-10-15T16:00",
            // Extended hours run until 20:00.
            "2026-10-14T16:30:00, true,  2026-10-14T20:00",
            "2026-10-14T21:00:00, true,  2026-10-15T20:00",
            // Friday evening and the weekend: Monday.
            "2026-10-16T18:00:00, false, 2026-10-19T16:00",
            "2026-10-18T12:00:00, true,  2026-10-19T20:00",
            // Thanksgiving: the next day, an early close.
            "2026-11-26T10:00:00, false, 2026-11-27T13:00",
            "2026-11-26T10:00:00, true,  2026-11-27T17:00"
    })
    void dayOrdersEndWithTheirTradingDay(String placed, boolean extendedHours, String expires) {
        assertThat(SessionPolicy.dayExpiry(eastern(placed), extendedHours).toInstant())
                .isEqualTo(eastern(expires + ":00").toInstant());
    }

    @Test
    @DisplayName("With market hours off a DAY order still lasts until midnight ET, and GTC is unchanged")
    void expiryWithMarketHoursOff() {
        OffsetDateTime placed = eastern("2026-10-14T16:30:00");
        MarketClock real = new MarketClock(Clock.systemUTC(), MarketClock.Mode.REAL, null, null);

        assertThat(SessionPolicy.expiryOf(Order.TimeInForce.DAY, placed, false, MarketClock.alwaysOpen()))
                .isEqualTo(OrderSubmissionService.expiryOf(Order.TimeInForce.DAY, placed));
        assertThat(SessionPolicy.expiryOf(Order.TimeInForce.DAY, placed, false, real).toInstant())
                .isEqualTo(eastern("2026-10-15T16:00:00").toInstant());
        assertThat(SessionPolicy.expiryOf(Order.TimeInForce.GTC, placed, false, real))
                .isEqualTo(placed.plusDays(90));
    }
}
