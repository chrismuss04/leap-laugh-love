package com.leap.leaplaughlove.common.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/**
 * The exception mappings every service shares, rendering errors as a JSON body of
 * {@code error} and {@code message}. Each service's {@code @RestControllerAdvice} extends this
 * and adds its own.
 */
public abstract class BaseGlobalExceptionHandler {

    /**
     * Builds the JSON error response.
     * @param status the HTTP status to respond with
     * @param error the machine-readable error code
     * @param message the human-readable detail
     * @return the error response
     */
    protected static ResponseEntity<Map<String, String>> error(HttpStatusCode status, String error, String message) {
        return ResponseEntity.status(status).body(Map.of(
                "error", error,
                "message", message));
    }

    /**
     * Handles a {@link ResponseStatusException}, returning its status and reason as JSON.
     * @param ex the exception raised by a controller
     * @return the mapped error response
     */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> handleResponseStatus(ResponseStatusException ex) {
        return error(ex.getStatusCode(), ex.getStatusCode().toString(),
                ex.getReason() != null ? ex.getReason() : ex.getMessage());
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
        return error(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", detail);
    }

    /**
     * Handles an {@link IllegalArgumentException}, returning a 400 response with its message.
     * @param ex the exception raised by a controller
     * @return the mapped error response
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleIllegalArgument(IllegalArgumentException ex) {
        return error(HttpStatus.BAD_REQUEST, "BAD_REQUEST", ex.getMessage());
    }
}
