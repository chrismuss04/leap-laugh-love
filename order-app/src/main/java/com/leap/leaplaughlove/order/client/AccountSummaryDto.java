package com.leap.leaplaughlove.order.client;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Data transfer object representing a summary of an account.
 * 
 * @param accountId the unique identifier of the account
 * @param accountNumber the account number
 * @param status the status of the account
 * @param baseCurrency the base currency of the account
 * @param tradingEnabled indicates if trading is enabled for the account
 * @param createdAt the timestamp when the account was created
 */
public record AccountSummaryDto(
        UUID accountId,
        String accountNumber,
        String status,
        String baseCurrency,
        boolean tradingEnabled,
        OffsetDateTime createdAt
) {}

