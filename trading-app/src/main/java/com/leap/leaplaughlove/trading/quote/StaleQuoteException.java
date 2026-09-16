package com.leap.leaplaughlove.trading.quote;

/**
 * Thrown when the current quote for an instrument is too old to price an order against, so
 * the order would not reflect the market.
 */
public class StaleQuoteException extends RuntimeException {

    /**
     * Constructs a new StaleQuoteException with the specified detail message.
     * @param message the detail message describing which quote was rejected and why
     */
    public StaleQuoteException(String message) {
        super(message);
    }
}
