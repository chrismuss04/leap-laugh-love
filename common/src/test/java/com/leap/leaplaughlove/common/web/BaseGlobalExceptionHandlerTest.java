package com.leap.leaplaughlove.common.web;

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
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("BaseGlobalExceptionHandler Unit Tests")
class BaseGlobalExceptionHandlerTest {

    private final BaseGlobalExceptionHandler handler = new BaseGlobalExceptionHandler() { };

    @Test
    @DisplayName("maps a ResponseStatusException's status and reason")
    void responseStatusWithReason() {
        ResponseEntity<Map<String, String>> response =
                handler.handleResponseStatus(new ResponseStatusException(HttpStatus.NOT_FOUND, "Not here"));

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertEquals(Map.of("error", "404 NOT_FOUND", "message", "Not here"), response.getBody());
    }

    @Test
    @DisplayName("falls back to the exception message when there is no reason")
    void responseStatusWithoutReason() {
        ResponseEntity<Map<String, String>> response =
                handler.handleResponseStatus(new ResponseStatusException(HttpStatus.FORBIDDEN));

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        assertTrue(response.getBody().get("message").contains("403"));
    }

    @Test
    @DisplayName("lists every field error, or a generic message when there are none")
    void validation() throws NoSuchMethodException {
        MethodParameter parameter = new MethodParameter(getClass().getDeclaredMethod("validation"), -1);
        BeanPropertyBindingResult withErrors = new BeanPropertyBindingResult(new Object(), "request");
        withErrors.addError(new FieldError("request", "quantity", "must be positive"));
        withErrors.addError(new FieldError("request", "symbol", "must not be blank"));

        ResponseEntity<Map<String, String>> response =
                handler.handleValidation(new MethodArgumentNotValidException(parameter, withErrors));
        ResponseEntity<Map<String, String>> empty = handler.handleValidation(
                new MethodArgumentNotValidException(parameter, new BeanPropertyBindingResult(new Object(), "request")));

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("VALIDATION_FAILED", response.getBody().get("error"));
        assertEquals("quantity: must be positive; symbol: must not be blank", response.getBody().get("message"));
        assertEquals("Validation failed", empty.getBody().get("message"));
    }

    @Test
    @DisplayName("maps an IllegalArgumentException to 400 BAD_REQUEST")
    void illegalArgument() {
        ResponseEntity<Map<String, String>> response =
                handler.handleIllegalArgument(new IllegalArgumentException("bad input"));

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals(Map.of("error", "BAD_REQUEST", "message", "bad input"), response.getBody());
    }
}
