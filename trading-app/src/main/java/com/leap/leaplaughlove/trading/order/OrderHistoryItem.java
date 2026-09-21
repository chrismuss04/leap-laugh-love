package com.leap.leaplaughlove.trading.order;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Record to represent an order history item in the trading system.
 * Has the order details including its status, timestamps, and execution details.
 *
 * <p>An order may fill in more than one execution, so the list is the authoritative field.
 * {@code execution} is kept as a derived accessor for callers written against the earlier
 * single-execution shape; it exposes the first fill only and is not meaningful for an order
 * that filled in several.
 *
 * @param orderId the unique identifier of the order
 * @param symbol the trading symbol of the order
 * @param side the side of the order (e.g., buy or sell)
 * @param quantity the quantity of the order
 * @param status the current status of the order
 * @param submittedAt the timestamp when the order was submitted
 * @param filledAt the timestamp when the order was filled
 * @param executions the executions that filled this order, oldest first (empty if
 *     unfilled/rejected)
 */
public record OrderHistoryItem(
        UUID orderId,
        String symbol,
        String side,
        Long quantity,
        String status,
        OffsetDateTime submittedAt,
        OffsetDateTime filledAt,
        List<ExecutionItem> executions
) {
    /**
     * Normalises a null execution list to an empty one, so callers never have to null-check it.
     */
    public OrderHistoryItem {
        executions = executions != null ? List.copyOf(executions) : List.of();
    }

    /**
     * Gets the first execution that filled this order.
     * @return the first execution, or null if the order has no fills
     */
    @JsonProperty("execution")
    public ExecutionItem execution() {
        return executions.isEmpty() ? null : executions.get(0);
    }
}
