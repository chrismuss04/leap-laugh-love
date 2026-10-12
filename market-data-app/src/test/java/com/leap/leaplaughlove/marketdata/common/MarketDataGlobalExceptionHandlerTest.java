package com.leap.leaplaughlove.marketdata.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DisplayName("MarketDataGlobalExceptionHandler Unit Tests")
class MarketDataGlobalExceptionHandlerTest {

    private final MarketDataGlobalExceptionHandler handler = new MarketDataGlobalExceptionHandler();

    @Test
    @DisplayName("swallows a write that failed because the client disconnected")
    void swallowsClientDisconnect() {
        assertDoesNotThrow(() -> handler.handleClientDisconnect(new IOException("Broken pipe")));
        assertDoesNotThrow(() -> handler.handleClientDisconnect(
                new AsyncRequestNotUsableException("ServletOutputStream failed to write: Broken pipe")));
    }

    @Test
    @DisplayName("rethrows any other I/O failure")
    void rethrowsOtherIoFailures() {
        IOException failure = new IOException("disk on fire");
        assertSame(failure, assertThrows(IOException.class, () -> handler.handleClientDisconnect(failure)));
    }

    @Test
    @DisplayName("maps a ResponseStatusException to its status and reason")
    void mapsResponseStatus() {
        ResponseEntity<Map<String, String>> response = handler.handleResponseStatus(
                new ResponseStatusException(HttpStatus.NOT_FOUND, "No such symbol"));

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertEquals("No such symbol", response.getBody().get("message"));
        assertEquals("404 NOT_FOUND", response.getBody().get("error"));
    }

    @Test
    @DisplayName("falls back to the exception message when there is no reason")
    void fallsBackToMessage() {
        ResponseEntity<Map<String, String>> response = handler.handleResponseStatus(
                new ResponseStatusException(HttpStatus.BAD_GATEWAY));

        assertEquals(HttpStatus.BAD_GATEWAY, response.getStatusCode());
        assertEquals(new ResponseStatusException(HttpStatus.BAD_GATEWAY).getMessage(),
                response.getBody().get("message"));
    }

    @Test
    @DisplayName("lists every field error of a validation failure")
    void mapsValidationFailure() throws Exception {
        BeanPropertyBindingResult result = new BeanPropertyBindingResult(new Object(), "request");
        result.addError(new FieldError("request", "symbol", "must not be blank"));
        result.addError(new FieldError("request", "size", "must be positive"));

        ResponseEntity<Map<String, String>> response =
                handler.handleValidation(new MethodArgumentNotValidException(parameter(), result));

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("VALIDATION_FAILED", response.getBody().get("error"));
        assertEquals("symbol: must not be blank; size: must be positive", response.getBody().get("message"));
    }

    @Test
    @DisplayName("reports a generic message when a validation failure has no field errors")
    void validationWithoutFieldErrors() throws Exception {
        ResponseEntity<Map<String, String>> response = handler.handleValidation(new MethodArgumentNotValidException(
                parameter(), new BeanPropertyBindingResult(new Object(), "request")));

        assertEquals("Validation failed", response.getBody().get("message"));
    }

    @Test
    @DisplayName("maps an IllegalArgumentException to a 400")
    void mapsIllegalArgument() {
        ResponseEntity<Map<String, String>> response =
                handler.handleIllegalArgument(new IllegalArgumentException("bad interval"));

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("BAD_REQUEST", response.getBody().get("error"));
        assertEquals("bad interval", response.getBody().get("message"));
    }

    private MethodParameter parameter() throws NoSuchMethodException {
        return new MethodParameter(getClass().getDeclaredMethod("target", String.class), 0);
    }

    @SuppressWarnings("unused")
    private void target(String unused) {
        // Only here so there is a method parameter to attach validation errors to.
    }
}
