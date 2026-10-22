package com.leap.leaplaughlove.account.portfolio;

import com.leap.leaplaughlove.common.market.MarketCalendar;
import com.leap.leaplaughlove.common.market.MarketSession;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;

/**
 * The value (cash plus holdings at market) of the authenticated client's accounts, or of one of them,
 * over a range.
 * @param range the range code that was charted, e.g. "1M"
 * @param intervalSeconds the spacing, in seconds, between points (the final point is "now" and
 *     may be closer to the one before it)
 * @param points the portfolio value at each point, oldest first. Intraday ranges skip the points
 *     that fall while the market is closed (nights, weekends, holidays) apart from the first and
 *     the last, so a chart spends its width on trading hours; daily ranges keep every day. 1D's
 *     first point is the previous close, then the last hour that traded before today as a
 *     lead-in, then today from its pre-market
 * @param startValue the value at the first point, the baseline the change is measured from
 * @param endValue the value at the last point
 * @param netContributions money deposited less money withdrawn after the first point, which moves
 *     the value without being a gain or loss
 * @param change the gain or loss: endValue minus startValue, less netContributions
 * @param changePercent the change as a percentage of startValue plus netContributions, or null if
 *     that is not positive
 * @param hours for 1D, the sessions of the trading day being charted, so a chart can lay the day
 *     out by time; null for other ranges and when market hours are off
 */
public record PortfolioHistoryResponse(
        String range,
        int intervalSeconds,
        List<Point> points,
        BigDecimal startValue,
        BigDecimal endValue,
        BigDecimal netContributions,
        BigDecimal change,
        BigDecimal changePercent,
        Hours hours
) {
    /**
     * One trading day's sessions, early close included.
     * @param preMarketOpen when pre-market began
     * @param regularOpen when regular trading began
     * @param regularClose when regular trading ended, or ends
     * @param afterHoursClose when after-hours trading ended, or ends
     */
    public record Hours(OffsetDateTime preMarketOpen, OffsetDateTime regularOpen, OffsetDateTime regularClose,
            OffsetDateTime afterHoursClose) {
        static Hours of(MarketCalendar.TradingDay day) {
            return new Hours(utc(day.preMarketOpen()), utc(day.regularOpen()), utc(day.regularClose()),
                    utc(day.afterHoursClose()));
        }

        private static OffsetDateTime utc(ZonedDateTime time) {
            return time.toOffsetDateTime().withOffsetSameInstant(ZoneOffset.UTC);
        }
    }

    /**
     * One valued point in time.
     * @param timestamp when the portfolio held this value
     * @param value the portfolio value: cash plus every holding at that time's price
     * @param contributions money deposited less money withdrawn since the first point, so the gain
     *     or loss to this point is value minus the first point's value minus this
     * @param session the market session of the stretch this point closes (the candle ending at
     *     its timestamp), so the 16:00 ET point is the regular close and the one after it is
     *     after-hours; always REGULAR when market hours are off
     */
    public record Point(OffsetDateTime timestamp, BigDecimal value, BigDecimal contributions,
            MarketSession session) {
    }
}

