package com.leap.leaplaughlove.account.balance;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * DTO that represents the balance response for an account, including individual account balances and the total balance by currency.
 * @param accounts the list of individual account balances
 * @param totalByCurrency the total balance grouped by currency
 */
public record BalanceResponse(
        List<AccountBalance> accounts,
        Map<String, BigDecimal> totalByCurrency
) {}

