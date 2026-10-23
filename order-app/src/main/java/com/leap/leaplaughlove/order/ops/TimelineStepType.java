package com.leap.leaplaughlove.order.ops;

/**
 * Steps of a trade's timeline, in lifecycle order; steps at the same instant sort by it.
 */
public enum TimelineStepType {
    /** A market quote was taken to price an execution. */
    PRICED,
    /** The client submitted the order. */
    SUBMITTED,
    /** The order passed validation. */
    ACCEPTED,
    /** The order failed validation. */
    REJECTED,
    /** An execution filled shares. */
    EXECUTED,
    /** An execution attempt was rejected. */
    EXECUTION_REJECTED,
    /** A cash ledger entry was made for the order. */
    CASH_SETTLED,
    /** The account's holding changed for the order. */
    POSITION_UPDATED,
    /** The order was filled. */
    FILLED
}
