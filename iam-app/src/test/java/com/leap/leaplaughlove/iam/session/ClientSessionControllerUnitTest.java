package com.leap.leaplaughlove.iam.session;

import com.leap.leaplaughlove.common.security.JwtService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("ClientSessionController unit tests: which tokens identify a session")
class ClientSessionControllerUnitTest {

    private final ClientSessionRepository sessions = mock(ClientSessionRepository.class);
    private final ClientSessionController controller = new ClientSessionController(sessions);
    private final UUID clientId = UUID.randomUUID();
    private final UUID sessionId = UUID.randomUUID();

    private Authentication authenticated(Object details) {
        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(clientId.toString(), null, List.of());
        authentication.setDetails(details);
        return authentication;
    }

    private JwtService.TokenIdentity identity(UUID session, Instant expiresAt, String purpose) {
        return new JwtService.TokenIdentity(clientId, session, expiresAt, purpose);
    }

    private void assertUnauthorized(Supplier<?> call) {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class, call::get);
        assertEquals(HttpStatus.UNAUTHORIZED, ex.getStatusCode());
        verify(sessions, never()).recordActivity(any(), any(), any());
        verify(sessions, never()).revoke(any(), any(), any());
    }

    @Test
    @DisplayName("rejects a request that has no authentication")
    void rejectsMissingAuthentication() {
        assertUnauthorized(() -> controller.activity(null));
        assertUnauthorized(() -> controller.logout(null));
    }

    @Test
    @DisplayName("rejects an authentication that is not marked authenticated")
    void rejectsUnauthenticated() {
        Authentication authentication = mock(Authentication.class);
        when(authentication.isAuthenticated()).thenReturn(false);

        assertUnauthorized(() -> controller.activity(authentication));
    }

    @Test
    @DisplayName("rejects details that are not a verified token identity")
    void rejectsForeignDetails() {
        assertUnauthorized(() -> controller.activity(authenticated("not an identity")));
        assertUnauthorized(() -> controller.logout(authenticated(null)));
    }

    @Test
    @DisplayName("rejects a token with no session, a purpose, or an expired lifetime")
    void rejectsUnusableTokens() {
        Instant future = Instant.now().plusSeconds(600);

        assertUnauthorized(() -> controller.activity(authenticated(identity(null, future, null))));
        assertUnauthorized(() -> controller.activity(authenticated(identity(sessionId, future, "password-reset"))));
        assertUnauthorized(() -> controller.activity(authenticated(identity(sessionId, Instant.now().minusSeconds(1), null))));
    }

    @Test
    @DisplayName("records activity for the token's own session")
    void recordsActivity() {
        when(sessions.recordActivity(eq(sessionId), eq(clientId), any())).thenReturn(true);

        ResponseEntity<Void> response = controller.activity(
                authenticated(identity(sessionId, Instant.now().plusSeconds(600), null)));

        assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
    }

    @Test
    @DisplayName("refuses activity for a session the repository no longer considers active")
    void refusesInactiveSession() {
        when(sessions.recordActivity(eq(sessionId), eq(clientId), any())).thenReturn(false);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () -> controller.activity(
                authenticated(identity(sessionId, Instant.now().plusSeconds(600), null))));

        assertEquals(HttpStatus.UNAUTHORIZED, ex.getStatusCode());
    }

    @Test
    @DisplayName("revokes only the token's own session on logout")
    void revokesOwnSession() {
        ResponseEntity<Void> response = controller.logout(
                authenticated(identity(sessionId, Instant.now().plusSeconds(600), null)));

        assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
        verify(sessions).revoke(eq(sessionId), eq(clientId), any());
    }
}
