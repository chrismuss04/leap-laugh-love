package com.leap.leaplaughlove.common.security;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedList;
import java.util.Arrays;
import java.util.Deque;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@DisplayName("Common security edge cases")
class SecurityEdgeCasesTest {

    private static final String SECRET = "thisisasecretkeyforjwtsigningandvalidation123456";

    private final JwtService jwt = new JwtService(SECRET, 60);

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("a history token is scoped to the market-history purpose and has no session")
    void historyToken() {
        UUID client = UUID.randomUUID();

        JwtService.TokenIdentity identity = jwt.parseIdentity(jwt.generateHistoryToken(client));

        assertEquals("market-history", identity.purpose());
        assertEquals(client, identity.clientId());
        assertNull(identity.sessionId());
    }

    @Test
    @DisplayName("a session token needs a session id and a lifetime that ends after it starts")
    void sessionTokenNeedsSessionAndLifetime() {
        Instant now = Instant.now();

        assertThrows(IllegalArgumentException.class,
                () -> jwt.generateToken(UUID.randomUUID(), "a@b.c", null, now, now.plusSeconds(60)));
        assertThrows(IllegalArgumentException.class,
                () -> jwt.generateToken(UUID.randomUUID(), "a@b.c", UUID.randomUUID(), now, now));
    }

    @Test
    @DisplayName("a token with no expiry is refused")
    void tokenWithoutExpiryIsRefused() {
        String token = Jwts.builder().subject(UUID.randomUUID().toString())
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8))).compact();

        assertThrows(IllegalArgumentException.class, () -> jwt.parseIdentity(token));
    }

    @Test
    @DisplayName("a token with no session claim parses to an identity without a session")
    void tokenWithoutSessionClaim() {
        String token = Jwts.builder().subject(UUID.randomUUID().toString())
                .expiration(Date.from(Instant.now().plusSeconds(60)))
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8))).compact();

        assertNull(jwt.parseIdentity(token).sessionId());
    }

    @Test
    @DisplayName("the session validator can be built from just a JdbcTemplate")
    void validatorPublicConstructor() {
        Deque<Integer> counts = new LinkedList<>(Arrays.asList(1, 0, null));
        JdbcTemplate jdbc = mock(JdbcTemplate.class,
                invocation -> "queryForObject".equals(invocation.getMethod().getName()) ? counts.poll() : null);
        ClientSessionValidator validator = new ClientSessionValidator(jdbc);
        UUID session = UUID.randomUUID();
        UUID client = UUID.randomUUID();
        Instant expiry = Instant.now().plusSeconds(600);

        assertTrue(validator.isActive(session, client, expiry));
        assertFalse(validator.isActive(session, client, expiry));
        assertFalse(validator.isActive(session, client, expiry));
        assertFalse(validator.isActive(null, client, expiry));
        assertFalse(validator.isActive(session, null, expiry));
        assertFalse(validator.isActive(session, client, null));
        assertFalse(validator.isActive(session, client, Instant.now().minusSeconds(1)));
    }

    @Test
    @DisplayName("the filter answers 503 and authenticates nobody when the session lookup fails")
    void filterAnswers503OnDatabaseFailure() throws Exception {
        JwtService service = mock(JwtService.class);
        ClientSessionValidator sessions = mock(ClientSessionValidator.class);
        JwtService.TokenIdentity identity = new JwtService.TokenIdentity(
                UUID.randomUUID(), UUID.randomUUID(), Instant.now().plusSeconds(600), null);
        when(service.parseIdentity("token")).thenReturn(identity);
        when(sessions.isActive(identity.role(), identity.sessionId(), identity.clientId(), identity.expiresAt()))
                .thenThrow(new QueryTimeoutException("database is down"));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        new JwtAuthenticationFilter(service, sessions).doFilterInternal(request, response, chain);

        assertEquals(503, response.getStatus());
        assertNull(SecurityContextHolder.getContext().getAuthentication());
        verifyNoInteractions(chain);
    }

    @Test
    @DisplayName("a blank or non-text principal is not a client")
    void blankAndForeignPrincipals() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("  ", null, List.of()));
        assertThrows(ResponseStatusException.class, SecurityUtils::getAuthenticatedClientId);

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(42, null, List.of()));
        assertThrows(ResponseStatusException.class, SecurityUtils::getAuthenticatedClientId);
    }

    @Test
    @DisplayName("an authentication that is not marked authenticated is refused")
    void unauthenticatedIsRefused() {
        org.springframework.security.core.Authentication authentication =
                mock(org.springframework.security.core.Authentication.class);
        when(authentication.isAuthenticated()).thenReturn(false);
        SecurityContextHolder.getContext().setAuthentication(authentication);

        assertThrows(ResponseStatusException.class, SecurityUtils::getAuthenticatedClientId);
    }
}
