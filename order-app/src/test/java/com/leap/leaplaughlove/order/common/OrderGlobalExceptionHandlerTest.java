package com.leap.leaplaughlove.order.common;

import com.leap.leaplaughlove.order.quote.QuoteUnavailableException;
import com.leap.leaplaughlove.order.quote.StaleQuoteException;
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

@DisplayName("OrderGlobalExceptionHandler Unit Tests")
class OrderGlobalExceptionHandlerTest {

    private OrderGlobalExceptionHandler handler;

    @BeforeEach
    void setUp() {
        handler = new OrderGlobalExceptionHandler();
    }

    @Test
    @DisplayName("handleResponseStatus maps status code and reason")
    void testHandleResponseStatusWithReason() {
        ResponseStatusException ex = new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found");
        ResponseEntity<Map<String, String>> response = handler.handleResponseStatus(ex);

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("404 NOT_FOUND", response.getBody().get("error"));
        assertEquals("Order not found", response.getBody().get("message"));
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
    @DisplayName("handleValidation formats field errors into BAD_REQUEST response")
    void testHandleValidation() throws NoSuchMethodException {
        BeanPropertyBindingResult bindingResult = new BeanPropertyBindingResult(new Object(), "orderRequest");
        bindingResult.addError(new FieldError("orderRequest", "quantity", "must be positive"));
        bindingResult.addError(new FieldError("orderRequest", "symbol", "must not be blank"));

        var method = this.getClass().getDeclaredMethod("setUp");
        MethodParameter parameter = new MethodParameter(method, -1);
        MethodArgumentNotValidException ex = new MethodArgumentNotValidException(parameter, bindingResult);

        ResponseEntity<Map<String, String>> response = handler.handleValidation(ex);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("VALIDATION_FAILED", response.getBody().get("error"));
        assertTrue(response.getBody().get("message").contains("quantity: must be positive"));
        assertTrue(response.getBody().get("message").contains("symbol: must not be blank"));
    }

    @Test
    @DisplayName("handleIllegalArgument returns BAD_REQUEST with exception message")
    void testHandleIllegalArgument() {
        IllegalArgumentException ex = new IllegalArgumentException("Invalid order side");
        ResponseEntity<Map<String, String>> response = handler.handleIllegalArgument(ex);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("BAD_REQUEST", response.getBody().get("error"));
        assertEquals("Invalid order side", response.getBody().get("message"));
    }

    @Test
    @DisplayName("handleStaleQuote returns CONFLICT with STALE_QUOTE error")
    void testHandleStaleQuote() {
        StaleQuoteException ex = new StaleQuoteException("Quote for AAPL is older than 5 seconds");
        ResponseEntity<Map<String, String>> response = handler.handleStaleQuote(ex);

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("STALE_QUOTE", response.getBody().get("error"));
        assertEquals("Quote for AAPL is older than 5 seconds", response.getBody().get("message"));
    }

    @Test
    @DisplayName("handleQuoteUnavailable returns SERVICE_UNAVAILABLE with QUOTE_UNAVAILABLE error")
    void testHandleQuoteUnavailable() {
        QuoteUnavailableException ex = new QuoteUnavailableException("Market data service is unreachable");
        ResponseEntity<Map<String, String>> response = handler.handleQuoteUnavailable(ex);

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("QUOTE_UNAVAILABLE", response.getBody().get("error"));
        assertEquals("Market data service is unreachable", response.getBody().get("message"));
    }
}

