package com.leap.leaplaughlove.common.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;

import java.util.Collections;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("SecurityUtils Tests")
class SecurityUtilsTest {

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("Returns UUID when principal is a UUID")
    void testGetAuthenticatedClientId_WithUuidPrincipal() {
        UUID clientId = UUID.randomUUID();
        var auth = new UsernamePasswordAuthenticationToken(clientId, null, Collections.emptyList());
        SecurityContextHolder.getContext().setAuthentication(auth);

        UUID result = SecurityUtils.getAuthenticatedClientId();
        assertEquals(clientId, result);
    }

    @Test
    @DisplayName("Returns UUID when principal is a valid UUID string")
    void testGetAuthenticatedClientId_WithStringPrincipal() {
        UUID clientId = UUID.randomUUID();
        var auth = new UsernamePasswordAuthenticationToken(clientId.toString(), null, Collections.emptyList());
        SecurityContextHolder.getContext().setAuthentication(auth);

        UUID result = SecurityUtils.getAuthenticatedClientId();
        assertEquals(clientId, result);
    }

    @Test
    @DisplayName("Throws 401 when authentication is null")
    void testGetAuthenticatedClientId_NullAuthentication() {
        SecurityContextHolder.clearContext();

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                SecurityUtils::getAuthenticatedClientId);
        assertEquals(HttpStatus.UNAUTHORIZED, ex.getStatusCode());
    }

    @Test
    @DisplayName("Throws 401 when principal is anonymousUser")
    void testGetAuthenticatedClientId_AnonymousUser() {
        var auth = new UsernamePasswordAuthenticationToken("anonymousUser", null);
        SecurityContextHolder.getContext().setAuthentication(auth);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                SecurityUtils::getAuthenticatedClientId);
        assertEquals(HttpStatus.UNAUTHORIZED, ex.getStatusCode());
    }

    @Test
    @DisplayName("Throws 401 when principal is not a valid UUID string")
    void testGetAuthenticatedClientId_InvalidStringPrincipal() {
        var auth = new UsernamePasswordAuthenticationToken("not-a-uuid", null, Collections.emptyList());
        SecurityContextHolder.getContext().setAuthentication(auth);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                SecurityUtils::getAuthenticatedClientId);
        assertEquals(HttpStatus.UNAUTHORIZED, ex.getStatusCode());
    }
}
