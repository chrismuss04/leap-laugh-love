package com.leap.leaplaughlove.order.template;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.leap.leaplaughlove.order.order.Order;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * A ticket setup to save. Offsets are signed percent from the price the side fills at (the ask for
 * a buy, the bid for a sell); a bracket's two percents are measured from the entry price.
 *
 * @param name what the client calls it, up to 40 characters
 * @param side BUY or SELL
 * @param orderType the order type to set up
 * @param timeInForce DAY (when null) or GTC, for orders other than MARKET
 * @param limitOffsetPercent for LIMIT and STOP_LIMIT: the limit price as percent from the market
 * @param stopOffsetPercent for STOP and STOP_LIMIT: the stop price as percent from the market
 * @param trailPercent for TRAILING_STOP: how many percent the stop trails
 * @param takeProfitPercent for a BUY bracket: percent above the entry price to take profit at
 * @param stopLossPercent for a BUY bracket: percent below the entry price to cut losses at
 */
public record OrderTemplateRequest(
        @NotBlank(message = "name is required")
        @Size(max = 40, message = "name may have at most 40 characters")
        String name,

        @NotNull(message = "side is required (BUY or SELL)")
        Order.Side side,

        @NotNull(message = "orderType is required")
        Order.OrderType orderType,

        Order.TimeInForce timeInForce,

        @DecimalMin(value = "-99.00", message = "limitOffsetPercent must be between -99 and 1000")
        @DecimalMax(value = "1000.00", message = "limitOffsetPercent must be between -99 and 1000")
        @Digits(integer = 4, fraction = 2, message = "limitOffsetPercent may have at most two decimal places")
        BigDecimal limitOffsetPercent,

        @DecimalMin(value = "-99.00", message = "stopOffsetPercent must be between -99 and 1000")
        @DecimalMax(value = "1000.00", message = "stopOffsetPercent must be between -99 and 1000")
        @Digits(integer = 4, fraction = 2, message = "stopOffsetPercent may have at most two decimal places")
        BigDecimal stopOffsetPercent,

        @DecimalMin(value = "0.10", message = "trailPercent must be between 0.1 and 50")
        @DecimalMax(value = "50.00", message = "trailPercent must be between 0.1 and 50")
        @Digits(integer = 2, fraction = 2, message = "trailPercent may have at most two decimal places")
        BigDecimal trailPercent,

        @DecimalMin(value = "0.01", message = "takeProfitPercent must be between 0.01 and 1000")
        @DecimalMax(value = "1000.00", message = "takeProfitPercent must be between 0.01 and 1000")
        @Digits(integer = 4, fraction = 2, message = "takeProfitPercent may have at most two decimal places")
        BigDecimal takeProfitPercent,

        @DecimalMin(value = "0.01", message = "stopLossPercent must be between 0.01 and 99")
        @DecimalMax(value = "99.00", message = "stopLossPercent must be between 0.01 and 99")
        @Digits(integer = 2, fraction = 2, message = "stopLossPercent may have at most two decimal places")
        BigDecimal stopLossPercent
) {

    /**
     * LIMIT and STOP_LIMIT templates need a limit offset, and no other type takes one.
     * @return false when the limit offset doesn't match the order type
     */
    @JsonIgnore
    @AssertTrue(message = "limitOffsetPercent is required for LIMIT and STOP_LIMIT templates, and only for them")
    public boolean isLimitMatchingType() {
        return orderType == null || orderType.hasLimit() == (limitOffsetPercent != null);
    }

    /**
     * STOP and STOP_LIMIT templates need a stop offset, and no other type takes one.
     * @return false when the stop offset doesn't match the order type
     */
    @JsonIgnore
    @AssertTrue(message = "stopOffsetPercent is required for STOP and STOP_LIMIT templates, and only for them")
    public boolean isStopMatchingType() {
        return orderType == null || orderType.takesStopPrice() == (stopOffsetPercent != null);
    }

    /**
     * TRAILING_STOP templates need a trail, and no other type takes one.
     * @return false when the trail doesn't match the order type
     */
    @JsonIgnore
    @AssertTrue(message = "trailPercent is required for TRAILING_STOP templates, and only for them")
    public boolean isTrailMatchingType() {
        return orderType == null || orderType.hasTrail() == (trailPercent != null);
    }

    /**
     * A bracket needs both percents, and only a buy can have one.
     * @return false when only one percent is given, or a sell has a bracket
     */
    @JsonIgnore
    @AssertTrue(message = "A bracket needs both takeProfitPercent and stopLossPercent, and only a BUY can have one")
    public boolean isBracketValid() {
        boolean take = takeProfitPercent != null;
        boolean stop = stopLossPercent != null;
        return take == stop && (!take || side == Order.Side.BUY);
    }
}
