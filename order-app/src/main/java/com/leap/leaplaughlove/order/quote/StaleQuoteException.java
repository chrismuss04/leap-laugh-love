package com.leap.leaplaughlove.order.quote;

/**
 * Thrown when a quote is too old to be traded on.
 */
public class StaleQuoteException extends RuntimeException {
    /**
     * Constructs a new StaleQuoteException with the specified detail message.
     * @param message the detail message explaining the reason for the exception
     */
    public StaleQuoteException(String message) {
        super(message);
    }
}

