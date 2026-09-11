package com.leap.leaplaughlove.trading.balance;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;
/**
 * Record to represent a cash transaction response from the trading system.
 * @param cashLedgerId the unique identifier of the cash ledger entry
 * @param accountId the unique identifier of the account associated with this transaction
 * @param entryType the type of the transaction (e.g., deposit, withdrawal)
 * @param amount the amount of the transaction
 * @param currency the currency of the transaction
 * @param balanceAfter the account balance after the transaction
 * @param createdAt the timestamp when the transaction was created
 * @param description an optional description for the transaction
 */
public record CashTransactionResponse(
        UUID cashLedgerId,
        UUID accountId,
        String entryType,
        BigDecimal amount,
        String currency,
        BigDecimal balanceAfter,
        OffsetDateTime createdAt,
        String description
) {}
