package com.leap.leaplaughlove.order.report;

import java.time.LocalDate;
import java.util.List;

/**
 * Response for GET /api/order/reports/insights: the headline figures on a commercial analyst's
 * dashboard, read from reporting.orders and reporting.clients. The previous period is the same
 * number of days immediately before the requested one, so each figure can be shown with its
 * change. A period with no activity is not an error: its figures are zero.
 * @param from the first UTC day of the period, inclusive
 * @param to the last UTC day of the period, inclusive
 * @param previousFrom the first UTC day of the previous period, inclusive
 * @param previousTo the last UTC day of the previous period, inclusive
 * @param current the figures for the period
 * @param previous the figures for the previous period
 * @param topInstruments the period's most active instruments, most trades first
 */
public record BusinessInsights(
        LocalDate from,
        LocalDate to,
        LocalDate previousFrom,
        LocalDate previousTo,
        InsightFigures current,
        InsightFigures previous,
        List<InstrumentActivity> topInstruments
) {
    /**
     * Compact constructor ensuring an unmodifiable list of instruments.
     */
    public BusinessInsights {
        topInstruments = topInstruments != null ? List.copyOf(topInstruments) : List.of();
    }
}
