package com.leap.leaplaughlove.account.account;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;

import java.math.BigDecimal;

/**
 * Request to save an account's trading settings.
 * @param maxSlippagePercent the price tolerance in percent: an order is rejected when its
 *                           execution price is further than this from the price it was quoted,
 *                           in either direction. Between 0 and 10 with at most two decimals;
 *                           null clears the saved tolerance
 */
public record TradeSettingsRequest(
        @DecimalMin(value = "0.00", message = "maxSlippagePercent must be between 0 and 10")
        @DecimalMax(value = "10.00", message = "maxSlippagePercent must be between 0 and 10")
        @Digits(integer = 2, fraction = 2, message = "maxSlippagePercent may have at most two decimal places")
        BigDecimal maxSlippagePercent) {}
