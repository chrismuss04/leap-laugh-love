package com.leap.leaplaughlove.trading.order;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Record to represent an order history item in the trading system.
 * Has the order details including its status and timestamps.
 * @param orderId the unique identifier of the order
 * @param symbol the trading symbol of the order
 * @param side the side of the order (e.g., buy or sell)
 * @param quantity the quantity of the order
 * @param status the current status of the order
 * @param submittedAt the timestamp when the order was submitted
 * @param filledAt the timestamp when the order was filled
 */
public record OrderHistoryItem(
        UUID orderId,
        String symbol,
        String side,
        BigDecimal quantity,
        String status,
        OffsetDateTime submittedAt,
        OffsetDateTime filledAt
) {}
