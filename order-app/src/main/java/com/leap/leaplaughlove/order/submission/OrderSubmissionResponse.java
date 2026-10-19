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
 * @param execution the execution details of the order; null while the order is OPEN
 * @param accountBalanceAfter the account balance after the order execution
 * @param orderType MARKET, LIMIT, STOP or STOP_LIMIT
 * @param limitPrice the limit price, for LIMIT and STOP_LIMIT orders
 * @param stopPrice the stop price, for STOP and STOP_LIMIT orders
 * @param timeInForce DAY or GTC; null for a market order
 * @param expiresAt when an OPEN order expires if it hasn't filled
 * @param reservedCash the cash an OPEN buy holds back from buying power
 * @param trailAmount for a TRAILING_STOP, the dollars its stop trails the best price by
 * @param trailPercent for a TRAILING_STOP, the percent its stop trails the best price by
 * @param extendedHours whether a LIMIT order may also fill in pre-market and after-hours
 * @param collarPrice for a market order queued for the open, the most a buy pays or the least a
 *                    sell takes; null otherwise
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
        BigDecimal accountBalanceAfter,
        String orderType,
        BigDecimal limitPrice,
        BigDecimal stopPrice,
        String timeInForce,
        OffsetDateTime expiresAt,
        BigDecimal reservedCash,
        BigDecimal trailAmount,
        BigDecimal trailPercent,
        boolean extendedHours,
        BigDecimal collarPrice
) {
    /**
     * A response as returned before market hours existed: a regular-hours order.
     * @param orderId the ID of the submitted order
     * @param accountId the ID of the account under which the order was submitted
     * @param accountNumber the account number associated with the order
     * @param instrumentId the ID of the instrument being traded
     * @param symbol the symbol of the instrument being traded
     * @param side the side of the order
     * @param quantity the quantity of the order
     * @param status the status of the order
     * @param submittedAt the timestamp when the order was submitted
     * @param acceptedAt the timestamp when the order was accepted
     * @param rejectedAt the timestamp when the order was rejected
     * @param filledAt the timestamp when the order was filled
     * @param rejectionReason the reason for order rejection, if applicable
     * @param execution the execution details of the order
     * @param accountBalanceAfter the account balance after the order execution
     * @param orderType how the order is priced
     * @param limitPrice the limit price, if any
     * @param stopPrice the stop price, if any
     * @param timeInForce DAY or GTC, for an order that waits
     * @param expiresAt when an OPEN order expires
     * @param reservedCash the cash an OPEN buy holds back
     * @param trailAmount a trailing stop's trail in dollars
     * @param trailPercent a trailing stop's trail in percent
     */
    public OrderSubmissionResponse(UUID orderId, UUID accountId, String accountNumber, UUID instrumentId,
                                   String symbol, String side, long quantity, String status,
                                   OffsetDateTime submittedAt, OffsetDateTime acceptedAt,
                                   OffsetDateTime rejectedAt, OffsetDateTime filledAt, String rejectionReason,
                                   ExecutionDto execution, BigDecimal accountBalanceAfter, String orderType,
                                   BigDecimal limitPrice, BigDecimal stopPrice, String timeInForce,
                                   OffsetDateTime expiresAt, BigDecimal reservedCash, BigDecimal trailAmount,
                                   BigDecimal trailPercent) {
        this(orderId, accountId, accountNumber, instrumentId, symbol, side, quantity, status, submittedAt,
                acceptedAt, rejectedAt, filledAt, rejectionReason, execution, accountBalanceAfter, orderType,
                limitPrice, stopPrice, timeInForce, expiresAt, reservedCash, trailAmount, trailPercent, false, null);
    }

    /**
     * A market order's response, as returned before order types existed.
     * @param orderId the ID of the submitted order
     * @param accountId the ID of the account under which the order was submitted
     * @param accountNumber the account number associated with the order
     * @param instrumentId the ID of the instrument being traded
     * @param symbol the symbol of the instrument being traded
     * @param side the side of the order
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
    public OrderSubmissionResponse(UUID orderId, UUID accountId, String accountNumber, UUID instrumentId,
                                   String symbol, String side, long quantity, String status,
                                   OffsetDateTime submittedAt, OffsetDateTime acceptedAt,
                                   OffsetDateTime rejectedAt, OffsetDateTime filledAt, String rejectionReason,
                                   ExecutionDto execution, BigDecimal accountBalanceAfter) {
        this(orderId, accountId, accountNumber, instrumentId, symbol, side, quantity, status, submittedAt,
                acceptedAt, rejectedAt, filledAt, rejectionReason, execution, accountBalanceAfter,
                "MARKET", null, null, null, null, null, null, null, false, null);
    }

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

