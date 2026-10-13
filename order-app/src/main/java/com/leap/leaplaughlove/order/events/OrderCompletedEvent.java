package com.leap.leaplaughlove.order.events;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * The order-events message for an order whose lifecycle is complete, consumed by reporting-etl
 * into reporting.orders. Published once per order: FILLED, REJECTED after its execution was
 * refused at settlement, or CANCELLED or EXPIRED for an order that waited for its price and
 * never executed. Serialized as plain JSON with no Java type headers, so it is read
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
 * @param status FILLED, REJECTED, CANCELLED or EXPIRED
 * @param fillPrice price per share, or null unless filled
 * @param rejectionReason why it was rejected, or null when filled
 * @param submittedAt when the order was submitted
 * @param completedAt when it was filled, rejected, cancelled or expired
 * @param orderType MARKET, LIMIT, STOP, STOP_LIMIT or TRAILING_STOP
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
        OffsetDateTime completedAt,
        String orderType) {
}
