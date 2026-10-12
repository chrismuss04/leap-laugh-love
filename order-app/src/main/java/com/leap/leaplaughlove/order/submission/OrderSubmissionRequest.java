package com.leap.leaplaughlove.order.submission;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.util.UUID;

import com.leap.leaplaughlove.order.order.Order;

/**
 * Request payload for submitting an order.
 * Supports whole-share trading: quantity must be at least 1 whole share.
 *
 * <p>Market orders (the default) always fill at the live quote. {@code quotedPrice} is only the
 * price the client was shown; when a price tolerance applies - {@code maxSlippagePercent}, or else
 * the account's saved one - the order is rejected if the fill price is further than that from
 * {@code quotedPrice}.
 *
 * <p>Order types: LIMIT, STOP and STOP_LIMIT orders wait OPEN until the market reaches their
 * price, for as long as {@code timeInForce} allows (DAY when not given). A limit price already
 * caps the fill, so price tolerance applies to market orders only.
 *
 * @param accountId the account to trade in
 * @param symbol the instrument's symbol, used when instrumentId is absent
 * @param instrumentId the instrument's ID
 * @param side BUY or SELL
 * @param quantity the whole-share quantity
 * @param quotedPrice the price the client was quoted when placing the order
 * @param maxSlippagePercent the price tolerance in percent for this order, overriding the
 *                           account's saved one; requires quotedPrice
 * @param orderType MARKET (when null), LIMIT, STOP or STOP_LIMIT
 * @param limitPrice the worst price to fill at, for LIMIT and STOP_LIMIT orders
 * @param stopPrice the price that sets a STOP or STOP_LIMIT order working
 * @param timeInForce DAY (when null) or GTC, for orders other than MARKET
 */
public record OrderSubmissionRequest(
        @NotNull(message = "accountId is required")
        UUID accountId,

        String symbol,

        UUID instrumentId,

        @NotNull(message = "side is required (BUY or SELL)")
        Order.Side side,

        @Min(value = 1, message = "quantity must be at least 1 whole share")
        long quantity,

        @Positive(message = "quotedPrice must be greater than zero")
        BigDecimal quotedPrice,

        @DecimalMin(value = "0.00", message = "maxSlippagePercent must be between 0 and 10")
        @DecimalMax(value = "10.00", message = "maxSlippagePercent must be between 0 and 10")
        @Digits(integer = 2, fraction = 2, message = "maxSlippagePercent may have at most two decimal places")
        BigDecimal maxSlippagePercent,

        Order.OrderType orderType,

        @Positive(message = "limitPrice must be greater than zero")
        @Digits(integer = 12, fraction = 2, message = "limitPrice may have at most two decimal places")
        BigDecimal limitPrice,

        @Positive(message = "stopPrice must be greater than zero")
        @Digits(integer = 12, fraction = 2, message = "stopPrice may have at most two decimal places")
        BigDecimal stopPrice,

        Order.TimeInForce timeInForce
) {

    /**
     * A market order, as placed before order types existed.
     * @param accountId the account to trade in
     * @param symbol the instrument's symbol, used when instrumentId is absent
     * @param instrumentId the instrument's ID
     * @param side BUY or SELL
     * @param quantity the whole-share quantity
     * @param quotedPrice the price the client was quoted when placing the order
     * @param maxSlippagePercent the price tolerance in percent for this order
     */
    public OrderSubmissionRequest(UUID accountId, String symbol, UUID instrumentId, Order.Side side,
                                  long quantity, BigDecimal quotedPrice, BigDecimal maxSlippagePercent) {
        this(accountId, symbol, instrumentId, side, quantity, quotedPrice, maxSlippagePercent,
                null, null, null, null);
    }

    /**
     * The order type, MARKET when none was given.
     * @return the order type to place
     */
    @JsonIgnore
    public Order.OrderType effectiveOrderType() {
        return orderType != null ? orderType : Order.OrderType.MARKET;
    }

    /**
     * A tolerance means nothing without a quote to measure the price move from.
     * @return false when maxSlippagePercent is given without quotedPrice
     */
    @JsonIgnore
    @AssertTrue(message = "quotedPrice is required when maxSlippagePercent is set")
    public boolean isQuotedPricePresentForTolerance() {
        return maxSlippagePercent == null || quotedPrice != null;
    }

    /**
     * LIMIT and STOP_LIMIT orders need a limit price, and no other type takes one.
     * @return false when the limit price doesn't match the order type
     */
    @JsonIgnore
    @AssertTrue(message = "limitPrice is required for LIMIT and STOP_LIMIT orders, and only for them")
    public boolean isLimitPriceMatchingType() {
        return effectiveOrderType().hasLimit() == (limitPrice != null);
    }

    /**
     * STOP and STOP_LIMIT orders need a stop price, and no other type takes one.
     * @return false when the stop price doesn't match the order type
     */
    @JsonIgnore
    @AssertTrue(message = "stopPrice is required for STOP and STOP_LIMIT orders, and only for them")
    public boolean isStopPriceMatchingType() {
        return effectiveOrderType().hasStop() == (stopPrice != null);
    }

    /**
     * A limit price already caps what the order fills at, so tolerance is for market orders.
     * @return false when maxSlippagePercent is given for an order other than MARKET
     */
    @JsonIgnore
    @AssertTrue(message = "maxSlippagePercent applies to market orders only")
    public boolean isToleranceForMarketOnly() {
        return maxSlippagePercent == null || effectiveOrderType() == Order.OrderType.MARKET;
    }
}
