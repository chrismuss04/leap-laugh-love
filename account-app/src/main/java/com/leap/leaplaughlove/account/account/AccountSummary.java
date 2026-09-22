package com.leap.leaplaughlove.account.account;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * DTO that represents a summary of an account.
 * @param accountId the unique identifier of the account
 * @param accountNumber the account number
 * @param status the status of the account
 * @param baseCurrency the base currency of the account
 * @param tradingEnabled indicates if trading is enabled for the account
 * @param createdAt the timestamp when the account was created
 */
public record AccountSummary(
        UUID accountId,
        String accountNumber,
        String status,
        String baseCurrency,
        boolean tradingEnabled,
        OffsetDateTime createdAt
) {}

