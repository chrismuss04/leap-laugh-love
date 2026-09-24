package com.leap.leaplaughlove.account.settlement;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Settlement request DTO for account order settlement
 * @param orderId the unique identifier of the order, cannot be null
 * @param executionId the unique identifier of the execution, cannot be null
 * @param instrumentId the unique identifier of the instrument, cannot be null
 * @param symbol the symbol of the instrument, cannot be blank
 * @param side the side of the order (BUY or SELL), cannot be blank
 * @param quantity the quantity of the instrument in the order, must be at least 1
 * @param price the price at which the order was executed, must be at least 0.0001
 * @param executedAt the timestamp when the order was executed, cannot be null
 */
public record SettlementRequest(
        @NotNull UUID orderId,
        @NotNull UUID executionId,
        @NotNull UUID instrumentId,
        @NotBlank String symbol,
        @NotBlank String side,
        @Min(1) long quantity,
        @NotNull @DecimalMin("0.0001") BigDecimal price,
        @NotNull OffsetDateTime executedAt
) {}

