package com.leap.leaplaughlove.trading.balance;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/**
 * Record to represent a cash movement request in the trading system.
 * @param amount the amount of cash to be moved
 * @param description an optional description for the cash movement
 */
public record CashMovementRequest(
        @NotNull(message = "amount is required")
        @DecimalMin(value = "0.01", message = "amount must be greater than zero")
        BigDecimal amount,

        String description
) {}
