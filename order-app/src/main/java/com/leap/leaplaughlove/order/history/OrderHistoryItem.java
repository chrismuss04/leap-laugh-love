package com.leap.leaplaughlove.order.history;
    
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import com.leap.leaplaughlove.order.execution.ExecutionItem;

/**
 * Record to represent an order history item in the order system.
 * Supports multiple executions per order with backward-compatible execution accessor.
 * @param orderId the unique identifier of the order
 * @param symbol the trading symbol of the order
 * @param side the side of the order (e.g., buy or sell)
 * @param quantity the quantity of the order
 * @param status the current status of the order
 * @param submittedAt the timestamp when the order was submitted
 * @param filledAt the timestamp when the order was filled
 * @param executions the list of executions associated with the order
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
    public OrderHistoryItem {
        executions = executions != null ? List.copyOf(executions) : List.of();
    }

    /**
     * Returns the first execution associated with the order, if any.
     * @return the first execution item or null if no executions exist
     */
    @JsonProperty("execution")
    public ExecutionItem execution() {
        return executions.isEmpty() ? null : executions.get(0);
    }
}

