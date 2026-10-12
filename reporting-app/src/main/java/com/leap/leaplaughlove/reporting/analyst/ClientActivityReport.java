package com.leap.leaplaughlove.reporting.analyst;

import java.time.YearMonth;
import java.util.List;

/**
 * Each month's active clients split by what they did before, and the clients who stopped.
 * @param window the months reported on
 * @param months one entry per month in the window, oldest first
 */
public record ClientActivityReport(ReportWindow window, List<Month> months) {

    /**
     * One month's client activity. The active clients are exactly the new, returning and
     * reactivated ones; lapsed clients are not active in the month.
     * @param month the month
     * @param activeClients clients who completed at least one order, filled or rejected
     * @param newClients active for the first time ever
     * @param returningClients also active the month before
     * @param reactivatedClients active before, but not the month before
     * @param lapsedClients active the month before, but not this month
     */
    public record Month(YearMonth month, long activeClients, long newClients, long returningClients,
                        long reactivatedClients, long lapsedClients) {}
}
