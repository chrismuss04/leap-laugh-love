package com.leap.leaplaughlove.trading.order;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * Request payload for submitting an order.
 * Supports whole-share trading: quantity must be at least 1 whole share.
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

        BigDecimal price
) {}

