package com.leap.leaplaughlove.order.client;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Data transfer object representing a settlement request for an order.
 * 
 * @param orderId the unique identifier of the order
 * @param executionId the unique identifier of the execution
 * @param instrumentId the unique identifier of the instrument
 * @param symbol the symbol of the instrument
 * @param side the side of the order (e.g., buy or sell)
 * @param quantity the quantity of the order
 * @param price the price at which the order was executed
 * @param executedAt the timestamp when the order was executed
 */
public record SettlementRequest(
        UUID orderId,
        UUID executionId,
        UUID instrumentId,
        String symbol,
        String side,
        long quantity,
        BigDecimal price,
        OffsetDateTime executedAt
) {}

