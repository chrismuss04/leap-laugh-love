package com.leap.leaplaughlove.account.balance;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Request to move cash between two accounts owned by the authenticated client.
 * @param fromAccountId the account the cash leaves
 * @param toAccountId the account the cash arrives in
 * @param amount the amount to move; must be at least 0.01
 * @param description an optional note appended to both ledger descriptions
 */
public record CashTransferRequest(
        @NotNull UUID fromAccountId,
        @NotNull UUID toAccountId,
        @NotNull @DecimalMin("0.01") BigDecimal amount,
        String description) {}
