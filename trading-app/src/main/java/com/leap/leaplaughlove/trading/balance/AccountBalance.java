package com.leap.leaplaughlove.trading.balance;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Record to represent an account's balance
 * @param accountId the unique identifier of the account
 * @param accountNumber the account number
 * @param currency the currency of the account balance
 * @param balance the current balance of the account
 */
public record AccountBalance(
        UUID accountId,
        String accountNumber,
        String currency,
        BigDecimal balance
) {}
