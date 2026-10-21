package com.leap.leaplaughlove.account.portfolio;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * The value (cash plus holdings at market) of the authenticated client's accounts, or of one of them,
 * over a range.
 * @param range the range code that was charted, e.g. "1M"
 * @param intervalSeconds the spacing, in seconds, between points (the final point is "now" and
 *     may be closer to the one before it)
 * @param points the portfolio value at each point, oldest first
 * @param startValue the value at the first point, the baseline the change is measured from
 * @param endValue the value at the last point
 * @param netContributions money deposited less money withdrawn after the first point, which moves
 *     the value without being a gain or loss
 * @param change the gain or loss: endValue minus startValue, less netContributions
 * @param changePercent the change as a percentage of startValue plus netContributions, or null if
 *     that is not positive
 */
public record PortfolioHistoryResponse(
        String range,
        int intervalSeconds,
        List<Point> points,
        BigDecimal startValue,
        BigDecimal endValue,
        BigDecimal netContributions,
        BigDecimal change,
        BigDecimal changePercent
) {
    /**
     * One valued point in time.
     * @param timestamp when the portfolio held this value
     * @param value the portfolio value: cash plus every holding at that time's price
     */
    public record Point(OffsetDateTime timestamp, BigDecimal value) {
    }
}

