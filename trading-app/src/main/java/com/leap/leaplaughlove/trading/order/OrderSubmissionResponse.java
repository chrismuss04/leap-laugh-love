package com.leap.leaplaughlove.trading.order;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Response payload returned upon order submission and immediate execution processing.
 */
public record OrderSubmissionResponse(
        UUID orderId,
        UUID accountId,
        String accountNumber,
        UUID instrumentId,
        String symbol,
        String side,
        long quantity,
        String status,
        OffsetDateTime submittedAt,
        OffsetDateTime acceptedAt,
        OffsetDateTime rejectedAt,
        OffsetDateTime filledAt,
        String rejectionReason,
        ExecutionDto execution,
        BigDecimal accountBalanceAfter
) {
    public record ExecutionDto(
            UUID executionId,
            Long fillQuantity,
            BigDecimal fillPrice,
            String status,
            OffsetDateTime executedAt,
            String reason
    ) {}
}

