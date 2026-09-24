package com.leap.leaplaughlove.account.balance;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Represents the response for a cash transaction, including details of the transaction and the resulting balance.
 * @param cashLedgerId the unique identifier of the cash ledger entry
 * @param accountId the unique identifier of the account
 * @param entryType the type of the cash transaction (e.g., deposit, withdrawal)
 * @param amount the amount of the cash transaction
 * @param currency the currency of the cash transaction
 * @param balanceAfter the resulting balance after the transaction
 * @param createdAt the timestamp when the transaction was created
 * @param description a description of the cash transaction
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

