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
 * <p>Orders always fill at the live quote. {@code quotedPrice} is only the price the client was
 * shown; when a price tolerance applies - {@code maxSlippagePercent}, or else the account's saved
 * one - the order is rejected if the fill price is further than that from {@code quotedPrice}.
 *
 * @param accountId the account to trade in
 * @param symbol the instrument's symbol, used when instrumentId is absent
 * @param instrumentId the instrument's ID
 * @param side BUY or SELL
 * @param quantity the whole-share quantity
 * @param quotedPrice the price the client was quoted when placing the order
 * @param maxSlippagePercent the price tolerance in percent for this order, overriding the
 *                           account's saved one; requires quotedPrice
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
        BigDecimal maxSlippagePercent
) {

    /**
     * A tolerance means nothing without a quote to measure the price move from.
     * @return false when maxSlippagePercent is given without quotedPrice
     */
    @JsonIgnore
    @AssertTrue(message = "quotedPrice is required when maxSlippagePercent is set")
    public boolean isQuotedPricePresentForTolerance() {
        return maxSlippagePercent == null || quotedPrice != null;
    }
}
