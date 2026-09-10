package com.leap.leaplaughlove.trading.common;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
/**
 * Global exception handler for Trading app
 * handles various exceptions, maps to HTTP responses
 */
@RestControllerAdvice
public class TradingGlobalExceptionHandler {

    /**
     * Handles ResponseStatusException and maps it to an appropriate HTTP response.
     * @param ex the ResponseStatusException to handle
     * @return ResponseEntity the response entity containing the error details
     */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> handleResponseStatus(ResponseStatusException ex) {
        return ResponseEntity.status(ex.getStatusCode()).body(Map.of(
                "error", ex.getStatusCode().toString(),
                "message", ex.getReason() != null ? ex.getReason() : ex.getMessage()));
    }

    /**
     * Handles MethodArgumentNotValidException and maps it to an appropriate HTTP response
     * @param ex the MethodArgumentNotValidException to handle
     * @return ResponseEntity the response entity containing the error details
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
     * Handles IllegalArgumentException and maps it to an appropriate HTTP response.
     * @param ex the IllegalArgumentException to handle
     * @return ResponseEntity the response entity containing the error details
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleIllegalArgument(IllegalArgumentException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                "error", "BAD_REQUEST",
                "message", ex.getMessage()));
    }
}
