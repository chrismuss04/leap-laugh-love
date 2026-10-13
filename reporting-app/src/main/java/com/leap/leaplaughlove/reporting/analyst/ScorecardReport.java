package com.leap.leaplaughlove.reporting.analyst;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;

/**
 * The headline figures month by month, each with its change on the month before and on the same
 * month a year before.
 * @param window the months reported on
 * @param months one entry per month in the window, oldest first
 */
public record ScorecardReport(ReportWindow window, List<Month> months) {

    /**
     * One month's figures.
     * @param month the month
     * @param volume value of the orders filled in the month
     * @param filledOrders how many orders filled
     * @param averageTradeValue volume per filled order; null when nothing filled
     * @param activeClients clients who had at least one order execute, filled or rejected
     * @param registrations clients who signed up
     */
    public record Month(YearMonth month, Metric volume, Metric filledOrders, Metric averageTradeValue,
                        Metric activeClients, Metric registrations) {}

    /**
     * A figure and its change. A change is null when there is nothing to compare against: the
     * earlier figure is zero or missing, which includes months before the data starts.
     * @param value the month's figure
     * @param monthOverMonthPct percentage change on the month before
     * @param yearOverYearPct percentage change on the same month a year before
     */
    public record Metric(BigDecimal value, BigDecimal monthOverMonthPct, BigDecimal yearOverYearPct) {}
}
