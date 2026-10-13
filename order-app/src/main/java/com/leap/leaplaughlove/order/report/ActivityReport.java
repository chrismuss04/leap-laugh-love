package com.leap.leaplaughlove.order.report;

import java.time.LocalDate;
import java.util.List;

/**
 * Response for GET /api/order/reports/activity: a commercial analyst's view of trading activity
 * over a period, read from reporting.orders. A period with no trading activity is not an error:
 * it has empty buckets and {@link ActivityTotals#EMPTY} totals, which the dashboard shows as
 * "No data available".
 * @param from the first UTC day of the period, inclusive
 * @param to the last UTC day of the period, inclusive
 * @param granularity whether the buckets are days or ISO weeks
 * @param instrument the instrument reported on, or null for all instruments
 * @param totals the activity over the whole period
 * @param buckets the activity per day or week, oldest first
 */
public record ActivityReport(
        LocalDate from,
        LocalDate to,
        ReportGranularity granularity,
        ReportInstrument instrument,
        ActivityTotals totals,
        List<ActivityBucket> buckets
) {
    /**
     * Compact constructor ensuring an unmodifiable list of buckets.
     */
    public ActivityReport {
        buckets = buckets != null ? List.copyOf(buckets) : List.of();
    }
}
