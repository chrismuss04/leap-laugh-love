package com.leap.leaplaughlove.order.report;

import java.time.LocalDate;

/**
 * One day or week of an activity report. Only buckets with trading activity are included.
 * @param periodStart the UTC day, or the Monday that starts the ISO week
 * @param totals the activity within the bucket
 */
public record ActivityBucket(LocalDate periodStart, ActivityTotals totals) {
}
