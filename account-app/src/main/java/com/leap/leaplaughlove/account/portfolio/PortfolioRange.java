package com.leap.leaplaughlove.account.portfolio;

import com.leap.leaplaughlove.account.common.TradingDay;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;

/**
 * A chartable portfolio time range and the candle width used to value it. Each
 * width is one the
 * market data service both serves and retains for the whole range (5m for 7
 * days, 1h for 90 days,
 * 1d for 365 days), picked so a range comes back as a few hundred points at
 * most.
 */
public enum PortfolioRange {
    /**
     * Since the start of the trading day (see {@link TradingDay}), so the day's change is measured
     * from the same close as each holding's day change.
     */
    ONE_DAY("1D", null, 300),
    ONE_WEEK("1W", Duration.ofDays(7), 3600),
    ONE_MONTH("1M", Duration.ofDays(30), 3600),
    THREE_MONTHS("3M", Duration.ofDays(90), 86400),
    ONE_YEAR("1Y", Duration.ofDays(365), 86400),
    /**
     * Since the client's first account opened, capped at the daily candle
     * retention.
     */
    ALL("ALL", null, 86400);

    /** The furthest back any candle width is retained. */
    static final Duration MAX_LOOKBACK = Duration.ofDays(365);

    private final String code;
    private final Duration span;
    private final int intervalSeconds;

    /**
     * Construct a new PortfolioRange.
     * 
     * @param code            The code for the range.
     * @param span            The span of the range.
     * @param intervalSeconds The interval in seconds for the range.
     */
    PortfolioRange(String code, Duration span, int intervalSeconds) {
        this.code = code;
        this.span = span;
        this.intervalSeconds = intervalSeconds;
    }

    /**
     * Gets the short code the API accepts and echoes back, e.g. "1M".
     * 
     * @return the range code
     */
    public String code() {
        return code;
    }

    /**
     * Resolves the start and candle width of this range. A range keeps its whole span even when the
     * accounts it covers opened part way through, so a week is always a week; the time before then
     * is noted as {@code opened}, for the chart to show apart. ALL is the accounts' lifetime, so it
     * starts when the earliest opened.
     * 
     * @param now                 the end of the range, in UTC
     * @param earliestAccountOpen when the earliest charted account opened, or null if unknown
     * @return the resolved window
     */
    Window window(OffsetDateTime now, OffsetDateTime earliestAccountOpen) {
        OffsetDateTime from = switch (this) {
            case ONE_DAY -> TradingDay.startOf(now);
            case ALL -> now.minus(MAX_LOOKBACK);
            default -> now.minus(span);
        };
        boolean openedLater = earliestAccountOpen != null && earliestAccountOpen.isAfter(from);
        OffsetDateTime opened = openedLater ? earliestAccountOpen.withOffsetSameInstant(ZoneOffset.UTC) : null;
        if (this != ALL) {
            return new Window(from, intervalSeconds, opened);
        }
        if (openedLater) {
            from = opened;
        }
        // A young account would chart as one or two daily points; use the finest width that
        // still covers its whole life instead.
        Duration life = Duration.between(from, now);
        int width = life.compareTo(Duration.ofDays(2)) <= 0 ? 300
                : life.compareTo(Duration.ofDays(30)) <= 0 ? 3600
                        : 86400;
        return new Window(from, Math.min(width, intervalSeconds), null);
    }

    /**
     * Parses a range code case-insensitively.
     * 
     * @param code the code to parse, e.g. "1m"
     * @return the matching range
     * @throws ResponseStatusException with 400 BAD_REQUEST if the code is not
     *                                 recognised
     */
    public static PortfolioRange fromCode(String code) {
        return Arrays.stream(values())
                .filter(range -> range.code.equalsIgnoreCase(code))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "range must be one of " + Arrays.stream(values()).map(PortfolioRange::code).toList()));
    }

    /**
     * A resolved range: where it starts and the candle width that values it.
     * 
     * @param from            the start of the range
     * @param intervalSeconds the candle width, in seconds
     * @param opened          when the earliest charted account opened, if that was after the
     *                        range starts; null otherwise
     */
    record Window(OffsetDateTime from, int intervalSeconds, OffsetDateTime opened) {
    }
}
