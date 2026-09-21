package com.leap.leaplaughlove.order.validation;

/**
 * Result of validating an order against cash balance or existing position holdings.
 * @param isValid indicates whether the trade is valid
 * @param reason provides the reason for rejection if the trade is not valid
 */
public record TradeValidationResult(boolean isValid, String reason) {

    /**
     * Returns a TradeValidationResult indicating that the trade is accepted.
     * @return a TradeValidationResult with isValid set to true and reason set to null
     */
    public static TradeValidationResult accepted() {
        return new TradeValidationResult(true, null);
    }

    /**
     * Returns a TradeValidationResult indicating that the trade is rejected.
     * @param reason the reason for rejection
     * @return a TradeValidationResult with isValid set to false and the provided reason
     */
    public static TradeValidationResult rejected(String reason) {
        return new TradeValidationResult(false, reason);
    }
}

