package com.leap.leaplaughlove.reporting.analyst;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;

/**
 * How many of each month's sign-ups were still placing orders in the months after. Cohorts are the
 * window's sign-up months, so later cohorts have fewer months to show.
 * @param window the months reported on, both as sign-up months and as activity months
 * @param cohorts one entry per sign-up month in the window, oldest first
 */
public record RetentionReport(ReportWindow window, List<Cohort> cohorts) {

    /**
     * One sign-up month's clients and their activity from then to the end of the window.
     * @param cohort the month the clients signed up in
     * @param clients how many signed up
     * @param months one entry per month from the sign-up month to the window's last, oldest first
     */
    public record Cohort(YearMonth cohort, long clients, List<Cell> months) {}

    /**
     * The cohort's activity in one month.
     * @param monthsSinceSignUp 0 for the sign-up month itself
     * @param month the month
     * @param activeClients cohort clients who completed at least one order in the month
     * @param activePct active clients as a percentage of the cohort; null for an empty cohort
     */
    public record Cell(int monthsSinceSignUp, YearMonth month, long activeClients, BigDecimal activePct) {}
}
