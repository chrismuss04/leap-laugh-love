package com.leap.leaplaughlove.account.account;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * DTO that represents a summary of an account.
 * @param accountId the unique identifier of the account
 * @param accountNumber the account number
 * @param status the status of the account
 * @param baseCurrency the base currency of the account
 * @param tradingEnabled indicates if trading is enabled for the account
 * @param maxSlippagePercent the saved price tolerance in percent, or null when none is saved
 * @param createdAt the timestamp when the account was created
 * @param inactiveSince when the account became empty, if flagged inactive; otherwise null
 */
public record AccountSummary(
        UUID accountId,
        String accountNumber,
        String status,
        String baseCurrency,
        boolean tradingEnabled,
        BigDecimal maxSlippagePercent,
        OffsetDateTime createdAt,
        OffsetDateTime inactiveSince
) {}

