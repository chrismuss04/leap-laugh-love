package com.leap.leaplaughlove.account.quote;

/**
 * Thrown when the market data service cannot be reached or returns an error while fetching
 * the quote or price history for an instrument.
 */
public class QuoteUnavailableException extends RuntimeException {
    /**
     * Constructs a new QuoteUnavailableException with the specified detail message.
     * @param message the detail message explaining the reason for the exception
     */
    public QuoteUnavailableException(String message) {
        super(message);
    }

    /**
     * Constructs a new QuoteUnavailableException with the specified detail message and cause.
     * @param message the detail message explaining the reason for the exception
     * @param cause the underlying cause of the exception
     */
    public QuoteUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}

