package com.leap.leaplaughlove.balance;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record CashMovementRequest(
        @NotNull
        @DecimalMin(value = "0.01", message = "amount must be greater than zero")
        BigDecimal amount,

        @Size(max = 500, message = "description must be at most 500 characters")
        String description) {
}
