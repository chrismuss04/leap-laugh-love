package com.leap.leaplaughlove.order.working;

import com.leap.leaplaughlove.order.order.Order;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * An order waiting for the market to reach its price, as listed on the client's open orders.
 *
 * @param orderId the order
 * @param accountId the account it was placed on
 * @param symbol the instrument's symbol
 * @param side BUY or SELL
 * @param quantity whole shares ordered
 * @param status OPEN, or the status a cancel just moved it to
 * @param orderType LIMIT, STOP, STOP_LIMIT or TRAILING_STOP
 * @param limitPrice the limit price, for LIMIT and STOP_LIMIT
 * @param stopPrice the stop price, for STOP and STOP_LIMIT; a trailing stop's current stop
 * @param timeInForce DAY or GTC
 * @param submittedAt when it was placed
 * @param expiresAt when it expires if it hasn't filled
 * @param triggeredAt when a stop-limit's stop price was reached, so it now works as a limit
 * @param reservedCash the cash a buy holds back from buying power, null for a sell
 * @param trailAmount for a TRAILING_STOP, the dollars its stop trails the best price by
 * @param trailPercent for a TRAILING_STOP, the percent its stop trails the best price by
 * @param trailPeak for a TRAILING_STOP, the best price seen: the high for a sell, the low for a buy
 * @param parentOrderId for a bracket's exit order, the buy whose fill opened it
 * @param ocoGroupId for a bracket's exit order, the group its two legs share: one executes, the other is cancelled
 * @param bracketTakeProfitPrice for a buy with a bracket, the take-profit price it will open at its fill
 * @param bracketStopLossPrice for a buy with a bracket, the stop-loss price it will open at its fill
 * @param extendedHours whether a LIMIT order may also fill in pre-market and after-hours
 */
public record OpenOrderItem(
        UUID orderId,
        UUID accountId,
        String symbol,
        String side,
        long quantity,
        String status,
        String orderType,
        BigDecimal limitPrice,
        BigDecimal stopPrice,
        String timeInForce,
        OffsetDateTime submittedAt,
        OffsetDateTime expiresAt,
        OffsetDateTime triggeredAt,
        BigDecimal reservedCash,
        BigDecimal trailAmount,
        BigDecimal trailPercent,
        BigDecimal trailPeak,
        UUID parentOrderId,
        UUID ocoGroupId,
        BigDecimal bracketTakeProfitPrice,
        BigDecimal bracketStopLossPrice,
        boolean extendedHours
) {
    /**
     * Describes an order, whose instrument must be loaded.
     * @param order the order
     * @return its open-order item
     */
    public static OpenOrderItem of(Order order) {
        return new OpenOrderItem(
                order.getOrderId(),
                order.getAccountId(),
                order.getInstrument().getSymbol(),
                order.getSide().name(),
                order.getQuantity(),
                order.getStatus().name(),
                order.getOrderType().name(),
                order.getLimitPrice(),
                order.getStopPrice(),
                order.getTimeInForce() != null ? order.getTimeInForce().name() : null,
                order.getSubmittedAt(),
                order.getExpiresAt(),
                order.getTriggeredAt(),
                order.getReservedCash(),
                order.getTrailAmount(),
                order.getTrailPercent(),
                order.getTrailPeak(),
                order.getParentOrderId(),
                order.getOcoGroupId(),
                order.getBracketTakeProfitPrice(),
                order.getBracketStopLossPrice(),
                order.isExtendedHours());
    }
}
