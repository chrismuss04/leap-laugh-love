package com.leap.leaplaughlove.order.ops;

/**
 * Steps of a trade's timeline, in lifecycle order; steps at the same instant sort by it.
 */
public enum TimelineStepType {
    PRICED,
    SUBMITTED,
    ACCEPTED,
    REJECTED,
    EXECUTED,
    EXECUTION_REJECTED,
    CASH_SETTLED,
    POSITION_UPDATED,
    FILLED
}
