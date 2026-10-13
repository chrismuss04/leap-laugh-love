package com.leap.leaplaughlove.order.working;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.leap.leaplaughlove.order.order.Order;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * An edit to an OPEN order. Only the fields given change; each must apply to the order's type
 * (a limit price to LIMIT and STOP_LIMIT, a stop price to STOP and STOP_LIMIT, a trail to
 * TRAILING_STOP). The order keeps its ID and place in history.
 *
 * @param quantity the new whole-share quantity
 * @param limitPrice the new limit price
 * @param stopPrice the new stop price
 * @param trailAmount the new trail in dollars; replaces a percent trail
 * @param trailPercent the new trail in percent; replaces a dollar trail
 * @param timeInForce DAY or GTC; a change restarts the time the order may work for
 */
public record ModifyOrderRequest(
        @Min(value = 1, message = "quantity must be at least 1 whole share")
        Long quantity,

        @Positive(message = "limitPrice must be greater than zero")
        @Digits(integer = 12, fraction = 2, message = "limitPrice may have at most two decimal places")
        BigDecimal limitPrice,

        @Positive(message = "stopPrice must be greater than zero")
        @Digits(integer = 12, fraction = 2, message = "stopPrice may have at most two decimal places")
        BigDecimal stopPrice,

        @Positive(message = "trailAmount must be greater than zero")
        @Digits(integer = 12, fraction = 2, message = "trailAmount may have at most two decimal places")
        BigDecimal trailAmount,

        @DecimalMin(value = "0.10", message = "trailPercent must be between 0.1 and 50")
        @DecimalMax(value = "50.00", message = "trailPercent must be between 0.1 and 50")
        @Digits(integer = 2, fraction = 2, message = "trailPercent may have at most two decimal places")
        BigDecimal trailPercent,

        Order.TimeInForce timeInForce
) {

    /**
     * An edit that changes nothing is a mistake.
     * @return false when no field is given
     */
    @JsonIgnore
    @AssertTrue(message = "Give at least one thing to change")
    public boolean isChangingSomething() {
        return Stream.of(quantity, limitPrice, stopPrice, trailAmount, trailPercent, timeInForce)
                .anyMatch(Objects::nonNull);
    }

    /**
     * A trail is an amount or a percent, not both.
     * @return false when both are given
     */
    @JsonIgnore
    @AssertTrue(message = "Give trailAmount or trailPercent, not both")
    public boolean isOneTrail() {
        return trailAmount == null || trailPercent == null;
    }
}
