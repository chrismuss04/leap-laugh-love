package com.leap.leaplaughlove.order.client;

import java.math.BigDecimal;

/**
 * Data transfer object representing the validation details of an account.
 *
 * @param accountActive indicates if the account is active
 * @param tradingEnabled indicates if trading is enabled for the account
 * @param cashBalance the cash balance of the account
 * @param holdingQuantity the quantity of holdings in the account
 * @param baseCurrency the base currency of the account
 * @param accountNumber the account number
 */
public record AccountValidationDto(
        boolean accountActive,
        boolean tradingEnabled,
        BigDecimal cashBalance,
        long holdingQuantity,
        String baseCurrency,
        String accountNumber
) {}

