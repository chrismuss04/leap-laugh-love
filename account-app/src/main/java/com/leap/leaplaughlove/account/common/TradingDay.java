package com.leap.leaplaughlove.account.common;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

/**
 * Where "today" begins for day-change figures. Market data strikes each daily close at midnight
 * UTC, and the frontend takes the previous close from that candle, so today's history and each
 * position's activity today are counted from the same moment.
 */
public final class TradingDay {

    private TradingDay() {
    }

    /**
     * The start of the trading day that contains a point in time.
     * @param now the point in time
     * @return midnight UTC on that day
     */
    public static OffsetDateTime startOf(OffsetDateTime now) {
        return now.withOffsetSameInstant(ZoneOffset.UTC).truncatedTo(ChronoUnit.DAYS);
    }
}
