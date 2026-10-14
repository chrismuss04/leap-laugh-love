package com.leap.leaplaughlove.account.common;

import com.leap.leaplaughlove.common.web.BaseGlobalExceptionHandler;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * Global exception handler for Account app.
 */
@RestControllerAdvice
public class AccountGlobalExceptionHandler extends BaseGlobalExceptionHandler {

    /**
     * Exception handler for IllegalStateException.
     * @param ex the IllegalStateException to handle
     * @return a ResponseEntity containing the error details
     */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> handleIllegalState(IllegalStateException ex) {
        return error(HttpStatus.BAD_REQUEST, "BAD_REQUEST", ex.getMessage());
    }
}
