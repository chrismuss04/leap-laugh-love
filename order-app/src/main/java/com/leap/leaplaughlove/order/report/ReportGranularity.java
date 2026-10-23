package com.leap.leaplaughlove.order.report;

/**
 * How an activity report breaks its period down: one bucket per UTC day, or one per ISO week
 * (Monday to Sunday, UTC).
 */
public enum ReportGranularity {
    /** One bucket per UTC day. */
    DAILY,
    /** One bucket per ISO week, Monday to Sunday, UTC. */
    WEEKLY
}
