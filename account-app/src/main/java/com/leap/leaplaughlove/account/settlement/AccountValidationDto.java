package com.leap.leaplaughlove.account.settlement;

import java.math.BigDecimal;

/**
 * Data Transfer Object for account validation information.
 * @param accountActive indicates if the account is active or not
 * @param tradingEnabled indicates if trading is enabled for the account
 * @param cashBalance the current cash balance of the account
 * @param holdingQuantity the quantity of the instrument held in the account
 * @param baseCurrency the base currency of the account
 * @param accountNumber the account number associated with the account
 */
public record AccountValidationDto(
        boolean accountActive,
        boolean tradingEnabled,
        BigDecimal cashBalance,
        long holdingQuantity,
        String baseCurrency,
        String accountNumber
) {}

