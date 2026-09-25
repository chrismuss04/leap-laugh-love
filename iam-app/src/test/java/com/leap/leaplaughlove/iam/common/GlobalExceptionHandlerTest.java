package com.leap.leaplaughlove.iam.common;

import com.leap.leaplaughlove.iam.auth.AccountLockedException;
import com.leap.leaplaughlove.iam.auth.InvalidCredentialsException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("GlobalExceptionHandler Unit Tests (IAM)")
class GlobalExceptionHandlerTest {

    private GlobalExceptionHandler handler;

    @BeforeEach
    void setUp() {
        handler = new GlobalExceptionHandler();
    }

    @Test
    @DisplayName("handleInvalidCredentials returns 401 UNAUTHORIZED with INVALID_CREDENTIALS error")
    void testHandleInvalidCredentials() {
        InvalidCredentialsException ex = new InvalidCredentialsException("Invalid password provided");
        ResponseEntity<Map<String, String>> response = handler.handleInvalidCredentials(ex);

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("INVALID_CREDENTIALS", response.getBody().get("error"));
        assertEquals("Invalid password provided", response.getBody().get("message"));
    }

    @Test
    @DisplayName("handleAccountLocked returns 423 LOCKED with ACCOUNT_LOCKED error")
    void testHandleAccountLocked() {
        AccountLockedException ex = new AccountLockedException("Too many failed login attempts");
        ResponseEntity<Map<String, String>> response = handler.handleAccountLocked(ex);

        assertEquals(HttpStatus.LOCKED, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("ACCOUNT_LOCKED", response.getBody().get("error"));
        assertEquals("Too many failed login attempts", response.getBody().get("message"));
    }

    @Test
    @DisplayName("handleValidation returns 400 BAD_REQUEST with VALIDATION_FAILED error")
    void testHandleValidation() throws NoSuchMethodException {
        BeanPropertyBindingResult bindingResult = new BeanPropertyBindingResult(new Object(), "loginRequest");
        bindingResult.addError(new FieldError("loginRequest", "email", "must not be blank"));
        bindingResult.addError(new FieldError("loginRequest", "password", "length must be >= 8"));

        var method = this.getClass().getDeclaredMethod("setUp");
        MethodParameter parameter = new MethodParameter(method, -1);
        MethodArgumentNotValidException ex = new MethodArgumentNotValidException(parameter, bindingResult);

        ResponseEntity<Map<String, String>> response = handler.handleValidation(ex);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("VALIDATION_FAILED", response.getBody().get("error"));
        assertTrue(response.getBody().get("message").contains("email: must not be blank"));
        assertTrue(response.getBody().get("message").contains("password: length must be >= 8"));
    }
}

