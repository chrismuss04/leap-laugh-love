package com.leap.leaplaughlove.order.ops;

/**
 * How many orders a search matches, in total and by outcome. awaitingExecution counts the orders
 * not yet final: SUBMITTED, ACCEPTED, or OPEN waiting for their price. Cancelled and expired
 * orders count only towards submitted.
 * @param submitted every matching order
 * @param filled the orders filled
 * @param rejected the orders rejected
 * @param awaitingExecution the orders not yet final
 */
public record OrderCounts(
        long submitted,
        long filled,
        long rejected,
        long awaitingExecution
) {
}
