package com.leap.leaplaughlove.common.security;

import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.io.IOException;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("JwtAuthenticationFilter Unit Tests")
class JwtAuthenticationFilterTest {

    @Mock
    private JwtService jwtService;

    @Mock
    private FilterChain filterChain;

    // Session Timeout & Revocation
    @Mock private ClientSessionValidator sessions;
    private JwtAuthenticationFilter filter;

    @BeforeEach
    void setUp() {
        filter = new JwtAuthenticationFilter(jwtService, sessions);
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("valid Bearer token sets authentication in SecurityContext")
    void testValidBearerTokenAuthenticates() throws ServletException, IOException {
        UUID clientId = UUID.randomUUID();
        String token = "valid-sample-token";

        var identity = new JwtService.TokenIdentity(clientId, UUID.randomUUID(), java.time.Instant.now().plusSeconds(3600), null);
        when(jwtService.parseIdentity(token)).thenReturn(identity);
        when(sessions.isActive(identity.sessionId(), clientId, identity.expiresAt())).thenReturn(true);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + token);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilterInternal(request, response, filterChain);

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertNotNull(auth);
        assertEquals(clientId, auth.getPrincipal());
        assertTrue(auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_CLIENT")));
        verify(filterChain).doFilter(request, response);
    }

    // Session Timeout & Revocation: valid signatures alone cannot authenticate inactive sessions.
    @Test
    void inactiveSessionDoesNotAuthenticate() throws Exception {
        var identity = new JwtService.TokenIdentity(UUID.randomUUID(), UUID.randomUUID(),
                java.time.Instant.now().plusSeconds(3600), null);
        when(jwtService.parseIdentity("token")).thenReturn(identity);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer token");
        filter.doFilterInternal(request, new MockHttpServletResponse(), filterChain);
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void unavailableStoreReturns503WithoutReachingController() throws Exception {
        var identity = new JwtService.TokenIdentity(UUID.randomUUID(), UUID.randomUUID(),
                java.time.Instant.now().plusSeconds(3600), null);
        when(jwtService.parseIdentity("token")).thenReturn(identity);
        when(sessions.isActive(identity.sessionId(), identity.clientId(), identity.expiresAt()))
                .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("offline"));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer token");
        var response = new MockHttpServletResponse();
        filter.doFilterInternal(request, response, filterChain);
        assertEquals(503, response.getStatus());
        org.mockito.Mockito.verifyNoInteractions(filterChain);
    }

    @Test
    void settlementTokensAreRestrictedToAccountInternalEndpoints() throws Exception {
        var identity = new JwtService.TokenIdentity(UUID.randomUUID(), null,
                java.time.Instant.now().plusSeconds(60), "account-settlement");
        when(jwtService.parseIdentity("token")).thenReturn(identity);
        String path = "/api/account/internal/accounts/" + UUID.randomUUID() + "/settlement";
        var request = new MockHttpServletRequest("POST", path);
        request.addHeader("Authorization", "Bearer token");
        filter.doFilterInternal(request, new MockHttpServletResponse(), filterChain);
        assertNull(SecurityContextHolder.getContext().getAuthentication());
        var accountFilter = new JwtAuthenticationFilter(jwtService, sessions, "account-settlement");
        accountFilter.doFilterInternal(request, new MockHttpServletResponse(), filterChain);
        assertNotNull(SecurityContextHolder.getContext().getAuthentication());
        request.setRequestURI("/api/account/balance");
        accountFilter.doFilterInternal(request, new MockHttpServletResponse(), filterChain);
        assertNull(SecurityContextHolder.getContext().getAuthentication());
        org.mockito.Mockito.verifyNoInteractions(sessions);
    }

    // Session Timeout & Revocation: history tokens allow only the backend seeding price read.
    @Test
    void historyTokensCannotOpenStreamsOrReachAccountEndpoints() throws Exception {
        var identity = new JwtService.TokenIdentity(UUID.randomUUID(), null,
                java.time.Instant.now().plusSeconds(60), "market-history");
        when(jwtService.parseIdentity("token")).thenReturn(identity);
        var marketFilter = new JwtAuthenticationFilter(jwtService, sessions, "market-history");
        var request = new MockHttpServletRequest("GET", "/api/marketdata/prices/AAPL/history");
        request.addHeader("Authorization", "Bearer token");
        marketFilter.doFilterInternal(request, new MockHttpServletResponse(), filterChain);
        assertNotNull(SecurityContextHolder.getContext().getAuthentication());
        request.setRequestURI("/api/marketdata/stream");
        marketFilter.doFilterInternal(request, new MockHttpServletResponse(), filterChain);
        assertNull(SecurityContextHolder.getContext().getAuthentication());
        request.setRequestURI("/api/account/internal/accounts/" + UUID.randomUUID() + "/validation-data");
        new JwtAuthenticationFilter(jwtService, sessions, "account-settlement")
                .doFilterInternal(request, new MockHttpServletResponse(), filterChain);
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    @DisplayName("request with no Authorization header passes through without authentication")
    void testNoAuthorizationHeader() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilterInternal(request, response, filterChain);

        assertNull(SecurityContextHolder.getContext().getAuthentication());
        verify(filterChain).doFilter(request, response);
    }

    @Test
    @DisplayName("request with non-Bearer Authorization header passes through without authentication")
    void testNonBearerAuthorizationHeader() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Basic dXNlcjpwYXNz");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilterInternal(request, response, filterChain);

        assertNull(SecurityContextHolder.getContext().getAuthentication());
        verify(filterChain).doFilter(request, response);
    }

    @Test
    @DisplayName("invalid Bearer token throwing JwtException clears SecurityContext")
    void testInvalidTokenThrowsJwtException() throws ServletException, IOException {
        String token = "malformed-token";
        when(jwtService.parseIdentity(token)).thenThrow(new JwtException("Invalid signature"));

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + token);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilterInternal(request, response, filterChain);

        assertNull(SecurityContextHolder.getContext().getAuthentication());
        verify(filterChain).doFilter(request, response);
    }

    @Test
    @DisplayName("invalid Bearer token throwing IllegalArgumentException clears SecurityContext")
    void testInvalidTokenThrowsIllegalArgumentException() throws ServletException, IOException {
        String token = "empty-claims-token";
        when(jwtService.parseIdentity(token)).thenThrow(new IllegalArgumentException("Token claims empty"));

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + token);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilterInternal(request, response, filterChain);

        assertNull(SecurityContextHolder.getContext().getAuthentication());
        verify(filterChain).doFilter(request, response);
    }
}

