package com.leap.leaplaughlove.account.balance;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Result of a transfer between two accounts.
 * @param transferId the ledger id of the withdrawal leg, which identifies the transfer
 * @param fromAccountId the account the cash left
 * @param toAccountId the account the cash arrived in
 * @param amount the amount moved
 * @param currency the currency of both accounts
 * @param fromBalanceAfter the source account's balance after the transfer
 * @param toBalanceAfter the destination account's balance after the transfer
 * @param createdAt when the transfer was booked
 */
public record CashTransferResponse(
        UUID transferId,
        UUID fromAccountId,
        UUID toAccountId,
        BigDecimal amount,
        String currency,
        BigDecimal fromBalanceAfter,
        BigDecimal toBalanceAfter,
        OffsetDateTime createdAt) {}
