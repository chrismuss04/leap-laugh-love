package com.leap.leaplaughlove.account.balance;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/**
 * Represents a request to move cash into or out of an account.
 * @param amount the amount of cash to be moved, not null or less than 0.01
 * @param description a description of the cash movement
 */
public record CashMovementRequest(
        @NotNull(message = "Amount is required")
        @DecimalMin(value = "0.01", message = "Amount must be at least 0.01")
        BigDecimal amount,
        String description
) {}

