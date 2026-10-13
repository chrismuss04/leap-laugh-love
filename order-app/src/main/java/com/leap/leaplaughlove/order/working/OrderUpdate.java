package com.leap.leaplaughlove.order.working;

import com.leap.leaplaughlove.order.order.Order;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Tells a client, as it happens, that one of their open orders ended without them doing anything:
 * it filled, was refused when its price was reached, or expired. Sent on the order stream.
 *
 * @param orderId the order
 * @param symbol the instrument's symbol
 * @param side BUY or SELL
 * @param quantity whole shares ordered
 * @param orderType LIMIT, STOP, STOP_LIMIT or TRAILING_STOP
 * @param status FILLED, ACCEPTED (it executed and is still being confirmed), REJECTED or EXPIRED
 * @param fillPrice the price it filled at, for FILLED and ACCEPTED
 * @param reason why it was rejected, for REJECTED
 * @param at when this happened
 */
public record OrderUpdate(
        UUID orderId,
        String symbol,
        String side,
        long quantity,
        String orderType,
        String status,
        BigDecimal fillPrice,
        String reason,
        OffsetDateTime at
) {
    /**
     * Describes how an order, whose instrument must be loaded, has just ended.
     * @param order the order
     * @param status how it ended
     * @param fillPrice the price it executed at, if it did
     * @param reason why it was rejected, if it was
     * @return the update to send
     */
    public static OrderUpdate of(Order order, Order.Status status, BigDecimal fillPrice, String reason) {
        return new OrderUpdate(
                order.getOrderId(),
                order.getInstrument().getSymbol(),
                order.getSide().name(),
                order.getQuantity(),
                order.getOrderType().name(),
                status.name(),
                fillPrice,
                reason,
                OffsetDateTime.now());
    }
}
