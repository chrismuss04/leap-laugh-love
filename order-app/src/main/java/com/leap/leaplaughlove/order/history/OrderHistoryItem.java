package com.leap.leaplaughlove.order.history;
    
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
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
 * @param orderType MARKET, LIMIT, STOP or STOP_LIMIT
 * @param limitPrice the limit price, for LIMIT and STOP_LIMIT orders
 * @param stopPrice the stop price, for STOP and STOP_LIMIT orders
 * @param completedAt when the order filled, was rejected, cancelled or expired - or was
 *                    submitted, while it is still working; history is listed newest first by this
 */
public record OrderHistoryItem(
        UUID orderId,
        String symbol,
        String side,
        Long quantity,
        String status,
        OffsetDateTime submittedAt,
        OffsetDateTime filledAt,
        List<ExecutionItem> executions,
        String orderType,
        BigDecimal limitPrice,
        BigDecimal stopPrice,
        OffsetDateTime completedAt
) {
    /**
     * Compact constructor ensuring an unmodifiable list of executions.
     */
    public OrderHistoryItem {
        executions = executions != null ? List.copyOf(executions) : List.of();
        orderType = orderType != null ? orderType : "MARKET";
        completedAt = completedAt != null ? completedAt : filledAt != null ? filledAt : submittedAt;
    }

    /**
     * A market order's history item.
     * @param orderId the unique identifier of the order
     * @param symbol the trading symbol of the order
     * @param side the side of the order
     * @param quantity the quantity of the order
     * @param status the current status of the order
     * @param submittedAt the timestamp when the order was submitted
     * @param filledAt the timestamp when the order was filled
     * @param executions the list of executions associated with the order
     */
    public OrderHistoryItem(UUID orderId, String symbol, String side, Long quantity, String status,
                            OffsetDateTime submittedAt, OffsetDateTime filledAt, List<ExecutionItem> executions) {
        this(orderId, symbol, side, quantity, status, submittedAt, filledAt, executions, "MARKET", null, null, null);
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

