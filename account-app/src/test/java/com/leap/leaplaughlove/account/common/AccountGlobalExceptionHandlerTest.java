package com.leap.leaplaughlove.account.common;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("AccountGlobalExceptionHandler Unit Tests")
class AccountGlobalExceptionHandlerTest {

    private AccountGlobalExceptionHandler handler;

    @BeforeEach
    void setUp() {
        handler = new AccountGlobalExceptionHandler();
    }

    @Test
    @DisplayName("handleResponseStatus maps status code and reason")
    void testHandleResponseStatusWithReason() {
        ResponseStatusException ex = new ResponseStatusException(HttpStatus.NOT_FOUND, "Account does not exist");
        ResponseEntity<Map<String, String>> response = handler.handleResponseStatus(ex);

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("404 NOT_FOUND", response.getBody().get("error"));
        assertEquals("Account does not exist", response.getBody().get("message"));
    }

    @Test
    @DisplayName("handleResponseStatus maps fallback message when reason is null")
    void testHandleResponseStatusWithoutReason() {
        ResponseStatusException ex = new ResponseStatusException(HttpStatus.FORBIDDEN);
        ResponseEntity<Map<String, String>> response = handler.handleResponseStatus(ex);

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        assertNotNull(response.getBody());
        assertTrue(response.getBody().get("message").contains("403"));
    }

    @Test
    @DisplayName("handleValidation formats validation errors into BAD_REQUEST response")
    void testHandleValidation() throws NoSuchMethodException {
        BeanPropertyBindingResult bindingResult = new BeanPropertyBindingResult(new Object(), "accountRequest");
        bindingResult.addError(new FieldError("accountRequest", "amount", "must be positive"));
        bindingResult.addError(new FieldError("accountRequest", "currency", "must be 3 characters"));

        var method = this.getClass().getDeclaredMethod("setUp");
        MethodParameter parameter = new MethodParameter(method, -1);
        MethodArgumentNotValidException ex = new MethodArgumentNotValidException(parameter, bindingResult);

        ResponseEntity<Map<String, String>> response = handler.handleValidation(ex);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("VALIDATION_FAILED", response.getBody().get("error"));
        assertTrue(response.getBody().get("message").contains("amount: must be positive"));
        assertTrue(response.getBody().get("message").contains("currency: must be 3 characters"));
    }

    @Test
    @DisplayName("handleIllegalArgument returns BAD_REQUEST with exception message")
    void testHandleIllegalArgument() {
        IllegalArgumentException ex = new IllegalArgumentException("Amount cannot be negative");
        ResponseEntity<Map<String, String>> response = handler.handleIllegalArgument(ex);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("BAD_REQUEST", response.getBody().get("error"));
        assertEquals("Amount cannot be negative", response.getBody().get("message"));
    }

    @Test
    @DisplayName("handleIllegalState returns BAD_REQUEST with exception message")
    void testHandleIllegalState() {
        IllegalStateException ex = new IllegalStateException("Insufficient funds for withdrawal");
        ResponseEntity<Map<String, String>> response = handler.handleIllegalState(ex);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("BAD_REQUEST", response.getBody().get("error"));
        assertEquals("Insufficient funds for withdrawal", response.getBody().get("message"));
    }
}

