package com.leap.leaplaughlove.trading.quote;

/**
 * Thrown when no current quote could be obtained for an instrument, either because the market
 * data service has not published one for the symbol or because it could not be reached.
 */
public class QuoteUnavailableException extends RuntimeException {

    /**
     * Constructs a new QuoteUnavailableException with the specified detail message.
     * @param message the detail message describing why no quote was available
     */
    public QuoteUnavailableException(String message) {
        super(message);
    }

    /**
     * Constructs a new QuoteUnavailableException with the specified detail message and cause.
     * @param message the detail message describing why no quote was available
     * @param cause the underlying failure encountered while calling the market data service
     */
    public QuoteUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
