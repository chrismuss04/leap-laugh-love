package com.leap.leaplaughlove.iam.common;

import com.leap.leaplaughlove.iam.auth.AccountLockedException;
import com.leap.leaplaughlove.iam.auth.InvalidCredentialsException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * Global exception handler for the IAM application.
 * Handles various exceptions and maps them to appropriate HTTP responses.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * Constructs a new GlobalExceptionHandler instance.
     */
    protected GlobalExceptionHandler() {
    }

    /**
     * Handles InvalidCredentialsException
     * @param  ex the exception indicating invalid credentials
     * @return ResponseEntity the response entity containing the error details
     */
    @ExceptionHandler(InvalidCredentialsException.class)
    public ResponseEntity<Map<String, String>> handleInvalidCredentials(InvalidCredentialsException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of(
                "error", "INVALID_CREDENTIALS",
                "message", ex.getMessage()));
    }

    /**
     * Handles the AccountLockedException
     * @param  ex the exception indicating the account is locked
     * @return ResponseEntity the response entity containing the error details
     */
    @ExceptionHandler(AccountLockedException.class)
    public ResponseEntity<Map<String, String>> handleAccountLocked(AccountLockedException ex) {
        return ResponseEntity.status(HttpStatus.LOCKED).body(Map.of(
                "error", "ACCOUNT_LOCKED",
                "message", ex.getMessage()));
    }

    /**
     * Handles the MethodArgumentNotValidException
     * @param  ex the exception containing validation errors
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
}
