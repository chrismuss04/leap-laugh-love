package com.leap.leaplaughlove.trading.order;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Record to represent an order history item in the trading system.
 * Has the order details including its status, timestamps, and execution details.
 *
 * @param orderId the unique identifier of the order
 * @param symbol the trading symbol of the order
 * @param side the side of the order (e.g., buy or sell)
 * @param quantity the quantity of the order
 * @param status the current status of the order
 * @param submittedAt the timestamp when the order was submitted
 * @param filledAt the timestamp when the order was filled
 * @param execution the single execution associated with this order (or null if unfilled/rejected)
 */
public record OrderHistoryItem(
        UUID orderId,
        String symbol,
        String side,
        Long quantity,
        String status,
        OffsetDateTime submittedAt,
        OffsetDateTime filledAt,
        ExecutionItem execution
) {
    /**
     * Serialized by Jackson as "executions" to maintain contract compatibility with
     * the frontend's expandable fills table.
     */
    @JsonProperty("executions")
    public List<ExecutionItem> executions() {
        return execution != null ? List.of(execution) : List.of();
    }
}
