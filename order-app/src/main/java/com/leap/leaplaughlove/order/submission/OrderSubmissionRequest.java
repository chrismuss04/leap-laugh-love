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
 * @param orderType MARKET (when null), LIMIT, STOP, STOP_LIMIT or TRAILING_STOP
 * @param limitPrice the worst price to fill at, for LIMIT and STOP_LIMIT orders
 * @param stopPrice the price that sets a STOP or STOP_LIMIT order working
 * @param timeInForce DAY (when null) or GTC, for orders other than MARKET
 * @param trailAmount for TRAILING_STOP: how many dollars behind the best price the stop trails
 * @param trailPercent for TRAILING_STOP: how many percent behind the best price the stop trails;
 *                     give this or trailAmount
 * @param bracketTakeProfitPrice for a BUY: the limit price of the sell order opened to take profit
 *                               once the buy fills; give this with bracketStopLossPrice
 * @param bracketStopLossPrice for a BUY: the stop price of the sell order opened to cut losses
 *                             once the buy fills; whichever sell executes first cancels the other
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

        Order.TimeInForce timeInForce,

        @Positive(message = "trailAmount must be greater than zero")
        @Digits(integer = 12, fraction = 2, message = "trailAmount may have at most two decimal places")
        BigDecimal trailAmount,

        @DecimalMin(value = "0.10", message = "trailPercent must be between 0.1 and 50")
        @DecimalMax(value = "50.00", message = "trailPercent must be between 0.1 and 50")
        @Digits(integer = 2, fraction = 2, message = "trailPercent may have at most two decimal places")
        BigDecimal trailPercent,

        @Positive(message = "bracketTakeProfitPrice must be greater than zero")
        @Digits(integer = 12, fraction = 2, message = "bracketTakeProfitPrice may have at most two decimal places")
        BigDecimal bracketTakeProfitPrice,

        @Positive(message = "bracketStopLossPrice must be greater than zero")
        @Digits(integer = 12, fraction = 2, message = "bracketStopLossPrice may have at most two decimal places")
        BigDecimal bracketStopLossPrice,

        Boolean extendedHours
) {

    /**
     * An order placed before market hours existed: it trades in regular hours only.
     * @param accountId the account to trade in
     * @param symbol the instrument's symbol, used when instrumentId is absent
     * @param instrumentId the instrument's ID
     * @param side BUY or SELL
     * @param quantity the whole-share quantity
     * @param quotedPrice the price the client was quoted when placing the order
     * @param maxSlippagePercent the price tolerance in percent for this order
     * @param orderType MARKET (when null), LIMIT, STOP, STOP_LIMIT or TRAILING_STOP
     * @param limitPrice the worst price to fill at, for LIMIT and STOP_LIMIT orders
     * @param stopPrice the price that sets a STOP or STOP_LIMIT order working
     * @param timeInForce DAY (when null) or GTC
     * @param trailAmount for TRAILING_STOP: the trail in dollars
     * @param trailPercent for TRAILING_STOP: the trail in percent
     * @param bracketTakeProfitPrice for a BUY: the take-profit sell's limit price
     * @param bracketStopLossPrice for a BUY: the stop-loss sell's stop price
     */
    public OrderSubmissionRequest(UUID accountId, String symbol, UUID instrumentId, Order.Side side,
                                  long quantity, BigDecimal quotedPrice, BigDecimal maxSlippagePercent,
                                  Order.OrderType orderType, BigDecimal limitPrice, BigDecimal stopPrice,
                                  Order.TimeInForce timeInForce, BigDecimal trailAmount, BigDecimal trailPercent,
                                  BigDecimal bracketTakeProfitPrice, BigDecimal bracketStopLossPrice) {
        this(accountId, symbol, instrumentId, side, quantity, quotedPrice, maxSlippagePercent, orderType,
                limitPrice, stopPrice, timeInForce, trailAmount, trailPercent, bracketTakeProfitPrice,
                bracketStopLossPrice, null);
    }

    /**
     * An order placed before bracket orders existed.
     * @param accountId the account to trade in
     * @param symbol the instrument's symbol, used when instrumentId is absent
     * @param instrumentId the instrument's ID
     * @param side BUY or SELL
     * @param quantity the whole-share quantity
     * @param quotedPrice the price the client was quoted when placing the order
     * @param maxSlippagePercent the price tolerance in percent for this order
     * @param orderType MARKET (when null), LIMIT, STOP, STOP_LIMIT or TRAILING_STOP
     * @param limitPrice the worst price to fill at, for LIMIT and STOP_LIMIT orders
     * @param stopPrice the price that sets a STOP or STOP_LIMIT order working
     * @param timeInForce DAY (when null) or GTC
     * @param trailAmount for TRAILING_STOP: the trail in dollars
     * @param trailPercent for TRAILING_STOP: the trail in percent
     */
    public OrderSubmissionRequest(UUID accountId, String symbol, UUID instrumentId, Order.Side side,
                                  long quantity, BigDecimal quotedPrice, BigDecimal maxSlippagePercent,
                                  Order.OrderType orderType, BigDecimal limitPrice, BigDecimal stopPrice,
                                  Order.TimeInForce timeInForce, BigDecimal trailAmount, BigDecimal trailPercent) {
        this(accountId, symbol, instrumentId, side, quantity, quotedPrice, maxSlippagePercent, orderType,
                limitPrice, stopPrice, timeInForce, trailAmount, trailPercent, null, null, null);
    }

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
                null, null, null, null, null, null, null, null, null);
    }

    /**
     * An order placed before trailing stops existed.
     * @param accountId the account to trade in
     * @param symbol the instrument's symbol, used when instrumentId is absent
     * @param instrumentId the instrument's ID
     * @param side BUY or SELL
     * @param quantity the whole-share quantity
     * @param quotedPrice the price the client was quoted when placing the order
     * @param maxSlippagePercent the price tolerance in percent for this order
     * @param orderType MARKET (when null), LIMIT, STOP or STOP_LIMIT
     * @param limitPrice the worst price to fill at, for LIMIT and STOP_LIMIT orders
     * @param stopPrice the price that sets a STOP or STOP_LIMIT order working
     * @param timeInForce DAY (when null) or GTC
     */
    public OrderSubmissionRequest(UUID accountId, String symbol, UUID instrumentId, Order.Side side,
                                  long quantity, BigDecimal quotedPrice, BigDecimal maxSlippagePercent,
                                  Order.OrderType orderType, BigDecimal limitPrice, BigDecimal stopPrice,
                                  Order.TimeInForce timeInForce) {
        this(accountId, symbol, instrumentId, side, quantity, quotedPrice, maxSlippagePercent, orderType,
                limitPrice, stopPrice, timeInForce, null, null, null, null, null);
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
     * Whether the order may also trade in pre-market and after-hours.
     * @return true only when asked for
     */
    @JsonIgnore
    public boolean isForExtendedHours() {
        return Boolean.TRUE.equals(extendedHours);
    }

    /**
     * Only limit orders trade in extended hours: thin markets make a market price - or a stop that
     * turns into one - unsafe there.
     * @return false when extendedHours is set on anything but a LIMIT order
     */
    @JsonIgnore
    @AssertTrue(message = "extendedHours applies to LIMIT orders only")
    public boolean isExtendedHoursForLimitOnly() {
        return !isForExtendedHours() || effectiveOrderType() == Order.OrderType.LIMIT;
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
        return effectiveOrderType().takesStopPrice() == (stopPrice != null);
    }

    /**
     * A TRAILING_STOP needs a trail as an amount or a percent - not both - and no other type takes one.
     * @return false when the trail doesn't match the order type
     */
    @JsonIgnore
    @AssertTrue(message = "TRAILING_STOP orders need exactly one of trailAmount and trailPercent, and only they take them")
    public boolean isTrailMatchingType() {
        boolean hasTrail = trailAmount != null || trailPercent != null;
        if (!effectiveOrderType().hasTrail()) {
            return !hasTrail;
        }
        return (trailAmount != null) != (trailPercent != null);
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

    /**
     * Whether the buy carries a bracket.
     * @return true if a take-profit or stop-loss was given
     */
    @JsonIgnore
    public boolean hasBracket() {
        return bracketTakeProfitPrice != null || bracketStopLossPrice != null;
    }

    /**
     * A bracket needs both exit prices.
     * @return false when only one of the take-profit and stop-loss is given
     */
    @JsonIgnore
    @AssertTrue(message = "A bracket needs both bracketTakeProfitPrice and bracketStopLossPrice")
    public boolean isBracketComplete() {
        return (bracketTakeProfitPrice == null) == (bracketStopLossPrice == null);
    }

    /**
     * The app has no short selling, so only a buy can be protected by sells.
     * @return false when a bracket is given on a sell
     */
    @JsonIgnore
    @AssertTrue(message = "A bracket can only be attached to a BUY order")
    public boolean isBracketOnBuyOnly() {
        return !hasBracket() || side == Order.Side.BUY;
    }

    /**
     * The take-profit must sit above the stop-loss.
     * @return false when the take-profit is not above the stop-loss
     */
    @JsonIgnore
    @AssertTrue(message = "bracketTakeProfitPrice must be above bracketStopLossPrice")
    public boolean isBracketOrdered() {
        return bracketTakeProfitPrice == null || bracketStopLossPrice == null
                || bracketTakeProfitPrice.compareTo(bracketStopLossPrice) > 0;
    }
}
