package com.leap.leaplaughlove.trading.balance;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Record to represent the balance response from the trading system.
 * @param accounts the list of account balances
 * @param totalsByCurrency the total balances grouped by currency
 */
public record BalanceResponse(
        List<AccountBalance> accounts,
        Map<String, BigDecimal> totalsByCurrency
) {}
