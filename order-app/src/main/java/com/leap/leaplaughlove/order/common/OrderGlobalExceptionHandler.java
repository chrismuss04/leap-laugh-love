package com.leap.leaplaughlove.order.common;

import com.leap.leaplaughlove.common.web.BaseGlobalExceptionHandler;
import com.leap.leaplaughlove.order.quote.QuoteUnavailableException;
import com.leap.leaplaughlove.order.quote.StaleQuoteException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * Global exception handler for Order app.
 */
@RestControllerAdvice
public class OrderGlobalExceptionHandler extends BaseGlobalExceptionHandler {

    /**
     * Handles StaleQuoteException and returns an appropriate HTTP response.
     * @param ex the StaleQuoteException to handle
     * @return a ResponseEntity containing the error details
     */
    @ExceptionHandler(StaleQuoteException.class)
    public ResponseEntity<Map<String, String>> handleStaleQuote(StaleQuoteException ex) {
        return error(HttpStatus.CONFLICT, "STALE_QUOTE", ex.getMessage());
    }

    /**
     * Handles QuoteUnavailableException and returns an appropriate HTTP response.
     * @param ex the QuoteUnavailableException to handle
     * @return a ResponseEntity containing the error details
     */
    @ExceptionHandler(QuoteUnavailableException.class)
    public ResponseEntity<Map<String, String>> handleQuoteUnavailable(QuoteUnavailableException ex) {
        return error(HttpStatus.SERVICE_UNAVAILABLE, "QUOTE_UNAVAILABLE", ex.getMessage());
    }
}
