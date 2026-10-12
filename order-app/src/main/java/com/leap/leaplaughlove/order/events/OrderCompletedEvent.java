package com.leap.leaplaughlove.order.events;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * The order-events message for an order whose lifecycle is complete, consumed by reporting-etl
 * into reporting.orders. Published once per order: FILLED, or REJECTED after its execution was
 * refused at settlement. Serialized as plain JSON with no Java type headers, so it is read
 * without any knowledge of this class.
 *
 * @param eventId unique per message
 * @param orderId the order, also the message key
 * @param accountId the account the order was placed on
 * @param clientId the client who owns that account
 * @param instrumentId the instrument traded
 * @param symbol the instrument's symbol
 * @param side BUY or SELL
 * @param quantity whole shares ordered; a fill is always for the whole quantity
 * @param status FILLED or REJECTED
 * @param fillPrice price per share, or null when rejected
 * @param rejectionReason why it was rejected, or null when filled
 * @param submittedAt when the order was submitted
 * @param completedAt when it was filled or rejected
 */
public record OrderCompletedEvent(
        UUID eventId,
        UUID orderId,
        UUID accountId,
        UUID clientId,
        UUID instrumentId,
        String symbol,
        String side,
        long quantity,
        String status,
        BigDecimal fillPrice,
        String rejectionReason,
        OffsetDateTime submittedAt,
        OffsetDateTime completedAt) {
}
