package com.leap.leaplaughlove.reporting.analyst;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;

/**
 * How much of each month's traded volume comes from its biggest clients.
 * @param window the months reported on
 * @param months one entry per month in the window, oldest first
 */
public record ConcentrationReport(ReportWindow window, List<Month> months) {

    /**
     * One month's volume and its top clients' share of it.
     * @param month the month
     * @param tradingClients clients with at least one filled order
     * @param volume value of the orders filled in the month
     * @param tiers the top 1, 10 and 20 percent of trading clients, in that order; empty in a
     *              month nobody traded in
     */
    public record Month(YearMonth month, long tradingClients, BigDecimal volume, List<Tier> tiers) {}

    /**
     * The top slice of a month's trading clients by volume.
     * @param topPct the slice, as a percentage of the month's trading clients
     * @param clients how many clients that is, rounded up to a whole client
     * @param volume their combined volume
     * @param sharePct their volume as a percentage of the month's
     */
    public record Tier(int topPct, long clients, BigDecimal volume, BigDecimal sharePct) {}
}
