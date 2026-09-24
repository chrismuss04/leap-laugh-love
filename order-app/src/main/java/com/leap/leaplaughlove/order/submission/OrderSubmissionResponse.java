package com.leap.leaplaughlove.order.submission;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Response payload returned upon order submission and immediate execution processing.
 * @param orderId the ID of the submitted order
 * @param accountId the ID of the account under which the order was submitted
 * @param accountNumber the account number associated with the order
 * @param instrumentId the ID of the instrument being traded
 * @param symbol the symbol of the instrument being traded
 * @param side the side of the order (e.g., BUY or SELL)
 * @param quantity the quantity of the order
 * @param status the status of the order
 * @param submittedAt the timestamp when the order was submitted
 * @param acceptedAt the timestamp when the order was accepted
 * @param rejectedAt the timestamp when the order was rejected
 * @param filledAt the timestamp when the order was filled
 * @param rejectionReason the reason for order rejection, if applicable
 * @param execution the execution details of the order
 * @param accountBalanceAfter the account balance after the order execution
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
        /**
         * Constructs an instance of ExecutionDto with the specified execution details.
         * @param executionId the ID of the execution
         * @param fillQuantity the quantity filled in the execution
         * @param fillPrice the price at which the execution was filled
         * @param status the status of the execution
         * @param executedAt the timestamp when the execution occurred
         * @param reason the reason for the execution status, if applicable
         */
    public record ExecutionDto(
            UUID executionId,
            Long fillQuantity,
            BigDecimal fillPrice,
            String status,
            OffsetDateTime executedAt,
            String reason
    ) {}
}

