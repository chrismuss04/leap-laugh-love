package com.leap.leaplaughlove.account.common;

import com.leap.leaplaughlove.common.market.MarketCalendar;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * Where "today" begins for day-change figures: the previous trading day's regular close (see
 * {@link MarketCalendar#previousClose}). Market data serves each symbol's price at that close, so
 * today's history, each position's activity today and each holding's previous close all count
 * from the same moment.
 */
public final class TradingDay {

    private TradingDay() {
    }

    /**
     * The start of the trading day under way at a point in time.
     * @param now the point in time
     * @return the previous trading day's regular close, in UTC
     */
    public static OffsetDateTime startOf(OffsetDateTime now) {
        return MarketCalendar.previousClose(now.toInstant()).atOffset(ZoneOffset.UTC);
    }
}
