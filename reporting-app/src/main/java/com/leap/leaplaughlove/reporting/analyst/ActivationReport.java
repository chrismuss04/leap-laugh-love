package com.leap.leaplaughlove.reporting.analyst;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;

/**
 * How many of each month's sign-ups went on to place an order, and how soon.
 * @param window the sign-up months reported on
 * @param cohorts one entry per sign-up month in the window, oldest first
 */
public record ActivationReport(ReportWindow window, List<Cohort> cohorts) {

    /**
     * One sign-up month's first orders. A first order is the client's first placed order of any
     * final status, the same as for an active client.
     * @param cohort the month the clients signed up in
     * @param registered how many signed up
     * @param orderedWithin30Days how many placed their first order within 30 days of signing up
     * @param orderedWithin30DaysPct the same as a percentage of the cohort; null for an empty cohort
     * @param orderedEver how many have placed an order at all so far
     * @param orderedEverPct the same as a percentage of the cohort; null for an empty cohort
     * @param medianDaysToFirstOrder median days from sign-up to first order, over the clients who
     *                               have ordered; null when none have
     * @param complete false while some of the cohort are still inside their first 30 days, so its
     *                 30-day figure can still rise and is not yet comparable with older cohorts
     */
    public record Cohort(YearMonth cohort, long registered, long orderedWithin30Days, BigDecimal orderedWithin30DaysPct,
                         long orderedEver, BigDecimal orderedEverPct, BigDecimal medianDaysToFirstOrder,
                         boolean complete) {}
}
