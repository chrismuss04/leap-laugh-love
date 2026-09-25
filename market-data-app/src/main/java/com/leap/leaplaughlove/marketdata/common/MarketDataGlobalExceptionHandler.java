package com.leap.leaplaughlove.marketdata.common;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.DisconnectedClientHelper;

import java.io.IOException;
import java.util.Map;

/**
 * Translates exceptions raised anywhere in the market data service into a consistent
 * JSON error body.
 */
@RestControllerAdvice
public class MarketDataGlobalExceptionHandler {

    private static final DisconnectedClientHelper DISCONNECTED_CLIENTS =
            new DisconnectedClientHelper(MarketDataGlobalExceptionHandler.class.getName());

    /**
     * Quietly drops a write that failed because the client went away - routine for the price
     * stream, whose subscribers disconnect whenever a tab closes, the frontend reconnects with a
     * new symbol set, or this service shuts down. Unhandled, each one reached the container and
     * was logged as an ERROR with a full stack trace. Any other I/O failure is rethrown.
     * @param ex the exception raised while writing the response
     * @throws Exception the same exception, if it wasn't a client disconnect
     */
    @ExceptionHandler({IOException.class, AsyncRequestNotUsableException.class})
    public void handleClientDisconnect(Exception ex) throws Exception {
        if (!DISCONNECTED_CLIENTS.checkAndLogClientDisconnectedException(ex)) {
            throw ex;
        }
    }

    /**
     * Handles a {@link ResponseStatusException}, returning its status and reason as JSON.
     * @param ex the exception raised by a controller
     * @return the mapped error response
     */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> handleResponseStatus(ResponseStatusException ex) {
        return ResponseEntity.status(ex.getStatusCode()).body(Map.of(
                "error", ex.getStatusCode().toString(),
                "message", ex.getReason() != null ? ex.getReason() : ex.getMessage()));
    }

    /**
     * Handles a bean validation failure, returning a 400 response listing every field error.
     * @param ex the validation exception raised by a controller
     * @return the mapped error response
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, String>> handleValidation(MethodArgumentNotValidException ex) {
        String detail = ex.getBindingResult().getFieldErrors().stream()
                .map(err -> err.getField() + ": " + err.getDefaultMessage())
                .reduce((a, b) -> a + "; " + b)
                .orElse("Validation failed");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                "error", "VALIDATION_FAILED",
                "message", detail));
    }

    /**
     * Handles an {@link IllegalArgumentException}, returning a 400 response with its message.
     * @param ex the exception raised by a controller
     * @return the mapped error response
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleIllegalArgument(IllegalArgumentException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                "error", "BAD_REQUEST",
                "message", ex.getMessage()));
    }
}
