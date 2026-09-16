package com.leap.leaplaughlove.trading.order;

/**
 * Result of validating an order against cash balance or existing position holdings.
 */
public record TradeValidationResult(boolean isValid, String reason) {

    public static TradeValidationResult accepted() {
        return new TradeValidationResult(true, null);
    }

    public static TradeValidationResult rejected(String reason) {
        return new TradeValidationResult(false, reason);
    }
}

