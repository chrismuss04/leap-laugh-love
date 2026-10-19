package com.leap.leaplaughlove.reporting.recap;

/**
 * Thrown when the market data service can't be reached, or answers with an error, while a recap's
 * daily closes are being fetched. A recap is never built from partial prices: the month is retried
 * later instead.
 */
public class RecapPricesUnavailableException extends RuntimeException {

    /**
     * Creates the exception.
     * @param message the detail message
     * @param cause the underlying client failure
     */
    public RecapPricesUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
