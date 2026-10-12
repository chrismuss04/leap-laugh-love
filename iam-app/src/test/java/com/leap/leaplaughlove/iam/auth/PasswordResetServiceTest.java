package com.leap.leaplaughlove.iam.auth;

import com.leap.leaplaughlove.iam.client.Client;
import com.leap.leaplaughlove.iam.client.ClientCredentials;
import com.leap.leaplaughlove.iam.client.ClientCredentialsRepository;
import com.leap.leaplaughlove.iam.client.ClientRepository;
import com.leap.leaplaughlove.iam.session.ClientSessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PasswordResetServiceTest {

    private static final String LINK_BASE_URL = "https://app.example.com/reset-password";
    private static final String EMAIL = "alice@example.com";

    @Mock private ClientRepository clientRepository;
    @Mock private ClientCredentialsRepository credentialsRepository;
    @Mock private PasswordResetTokenRepository tokenRepository;
    @Mock private ClientSessionRepository sessionRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private ApplicationEventPublisher eventPublisher;

    private PasswordResetService service;
    private UUID clientId;
    private Client testClient;
    private ClientCredentials testCredentials;

    @BeforeEach
    void setUp() {
        service = newService(30, LINK_BASE_URL);
        clientId = UUID.randomUUID();
        testClient = new Client(
                clientId, EMAIL, "555-0100", "ACTIVE", OffsetDateTime.now(),
                "Alice Johnson", LocalDate.of(1990, 1, 1), "123-45-6789",
                "123 Main St", null, "New York", "NY", "10001", "US",
                "INTERMEDIATE", new java.math.BigDecimal("1000.00"));
        testCredentials = new ClientCredentials(clientId, "$2a$10$oldhash", 0, null);
    }

    private PasswordResetService newService(long ttlMinutes, String linkBaseUrl) {
        return new PasswordResetService(clientRepository, credentialsRepository, tokenRepository,
                sessionRepository, passwordEncoder, eventPublisher, ttlMinutes, linkBaseUrl);
    }

    private static String sha256(String value) throws Exception {
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private PasswordResetRequestedEvent publishedEvent() {
        var event = ArgumentCaptor.forClass(PasswordResetRequestedEvent.class);
        verify(eventPublisher).publishEvent(event.capture());
        return event.getValue();
    }

    // ==================== requestReset ====================

    // Verify the link carries the token while only its hash is stored, with the configured lifetime.
    @Test
    void requestReset_StoresHashAndPublishesLink() throws Exception {
        when(clientRepository.findByEmail(EMAIL)).thenReturn(Optional.of(testClient));

        service.requestReset(EMAIL);

        var hash = ArgumentCaptor.forClass(String.class);
        var createdAt = ArgumentCaptor.forClass(Instant.class);
        var expiresAt = ArgumentCaptor.forClass(Instant.class);
        verify(tokenRepository).create(any(UUID.class), eq(clientId), hash.capture(),
                createdAt.capture(), expiresAt.capture());
        assertEquals(Duration.ofMinutes(30), Duration.between(createdAt.getValue(), expiresAt.getValue()));

        PasswordResetRequestedEvent event = publishedEvent();
        assertEquals(EMAIL, event.email());
        assertEquals(expiresAt.getValue(), event.expiresAt());
        assertTrue(event.resetLink().startsWith(LINK_BASE_URL + "?token="));
        String token = event.resetLink().substring((LINK_BASE_URL + "?token=").length());
        // 32 random bytes, base64url without padding: safe to put in a URL unescaped.
        assertTrue(token.matches("[A-Za-z0-9_-]{43}"));
        assertEquals(sha256(token), hash.getValue());
        assertNotEquals(token, hash.getValue());
    }

    // Verify earlier links stop working before the new one is stored.
    @Test
    void requestReset_ReplacesEarlierLinks() {
        when(clientRepository.findByEmail(EMAIL)).thenReturn(Optional.of(testClient));

        service.requestReset(EMAIL);

        var order = inOrder(tokenRepository);
        order.verify(tokenRepository).invalidateAll(eq(clientId), any(Instant.class));
        order.verify(tokenRepository).create(any(), eq(clientId), anyString(), any(), any());
    }

    // Verify every request gets its own token.
    @Test
    void requestReset_IssuesDistinctTokens() {
        when(clientRepository.findByEmail(EMAIL)).thenReturn(Optional.of(testClient));

        service.requestReset(EMAIL);
        service.requestReset(EMAIL);

        var hash = ArgumentCaptor.forClass(String.class);
        verify(tokenRepository, times(2)).create(any(), eq(clientId), hash.capture(), any(), any());
        assertNotEquals(hash.getAllValues().get(0), hash.getAllValues().get(1));
    }

    // Verify an unregistered email is silently ignored.
    @Test
    void requestReset_UnknownEmail_DoesNothing() {
        when(clientRepository.findByEmail("unknown@example.com")).thenReturn(Optional.empty());

        assertDoesNotThrow(() -> service.requestReset("unknown@example.com"));

        verifyNoInteractions(tokenRepository, eventPublisher);
    }

    // Verify a locked client can still ask for a link - a reset is their way back in.
    @Test
    void requestReset_LockedClient_IssuesLink() {
        testClient.setStatus("LOCKED");
        when(clientRepository.findByEmail(EMAIL)).thenReturn(Optional.of(testClient));

        service.requestReset(EMAIL);

        verify(tokenRepository).create(any(), eq(clientId), anyString(), any(), any());
        verify(eventPublisher).publishEvent(any(PasswordResetRequestedEvent.class));
    }

    // Verify deleted and pending clients get no link.
    @Test
    void requestReset_NonResettableStatus_DoesNothing() {
        for (String status : new String[]{"DELETED", "PENDING"}) {
            testClient.setStatus(status);
            when(clientRepository.findByEmail(EMAIL)).thenReturn(Optional.of(testClient));

            service.requestReset(EMAIL);
        }

        verifyNoInteractions(tokenRepository, eventPublisher);
    }

    // ==================== validateToken ====================

    // Verify a usable link passes the check without being spent.
    @Test
    void validateToken_UsableLink_Passes() throws Exception {
        when(tokenRepository.isUsable(eq(sha256("the-token")), any(Instant.class))).thenReturn(true);

        assertDoesNotThrow(() -> service.validateToken("the-token"));

        verify(tokenRepository, never()).consume(anyString(), any());
    }

    // Verify a used, replaced, expired or unknown link fails the check.
    @Test
    void validateToken_DeadLink_Throws() {
        when(tokenRepository.isUsable(anyString(), any(Instant.class))).thenReturn(false);

        assertThrows(InvalidResetTokenException.class, () -> service.validateToken("the-token"));
    }

    // ==================== resetPassword ====================

    private void stubValidToken() throws Exception {
        when(tokenRepository.consume(eq(sha256("the-token")), any(Instant.class))).thenReturn(Optional.of(clientId));
        when(credentialsRepository.findByClientId(clientId)).thenReturn(Optional.of(testCredentials));
        when(passwordEncoder.encode("NewPassword123")).thenReturn("$2a$10$newhash");
    }

    // Verify a valid token stores the new hash, spends the client's links and ends their sessions.
    @Test
    void resetPassword_Success() throws Exception {
        stubValidToken();

        service.resetPassword("the-token", "NewPassword123");

        assertEquals("$2a$10$newhash", testCredentials.getPasswordHash());
        verify(credentialsRepository).save(testCredentials);
        verify(tokenRepository).invalidateAll(eq(clientId), any(Instant.class));
        verify(sessionRepository).revokeAll(eq(clientId), any(Instant.class));
        // Not locked out, so the client's status is left alone.
        verify(clientRepository, never()).save(any());
    }

    // Verify a client locked out by failed logins is unlocked and their attempts cleared.
    @Test
    void resetPassword_UnlocksClientLockedByFailedAttempts() throws Exception {
        testCredentials = new ClientCredentials(clientId, "$2a$10$oldhash", 3, null);
        testClient.setStatus("LOCKED");
        stubValidToken();
        when(clientRepository.findById(clientId)).thenReturn(Optional.of(testClient));

        service.resetPassword("the-token", "NewPassword123");

        assertEquals("ACTIVE", testClient.getStatus());
        assertEquals(0, testCredentials.getFailedAttempts());
        verify(clientRepository).save(testClient);
    }

    // Verify failed attempts short of a lockout are cleared too.
    @Test
    void resetPassword_ClearsFailedAttempts() throws Exception {
        testCredentials = new ClientCredentials(clientId, "$2a$10$oldhash", 2, null);
        stubValidToken();

        service.resetPassword("the-token", "NewPassword123");

        assertEquals(0, testCredentials.getFailedAttempts());
        verify(clientRepository, never()).findById(any());
    }

    // Verify a lock that failed logins did not cause survives the reset.
    @Test
    void resetPassword_KeepsLockNotCausedByFailedAttempts() throws Exception {
        testClient.setStatus("LOCKED");
        stubValidToken();

        service.resetPassword("the-token", "NewPassword123");

        assertEquals("LOCKED", testClient.getStatus());
        assertEquals("$2a$10$newhash", testCredentials.getPasswordHash());
        verify(clientRepository, never()).save(any());
    }

    // Verify a client that reached the attempt limit but is not locked is not made active.
    @Test
    void resetPassword_DoesNotActivateNonLockedClient() throws Exception {
        testCredentials = new ClientCredentials(clientId, "$2a$10$oldhash", 3, null);
        testClient.setStatus("DELETED");
        stubValidToken();
        when(clientRepository.findById(clientId)).thenReturn(Optional.of(testClient));

        service.resetPassword("the-token", "NewPassword123");

        assertEquals("DELETED", testClient.getStatus());
        verify(clientRepository, never()).save(any());
    }

    // Verify an unknown, expired or used token changes nothing.
    @Test
    void resetPassword_InvalidToken_Throws() {
        when(tokenRepository.consume(anyString(), any(Instant.class))).thenReturn(Optional.empty());

        InvalidResetTokenException ex = assertThrows(InvalidResetTokenException.class,
                () -> service.resetPassword("bad-token", "NewPassword123"));

        assertTrue(ex.getMessage().contains("invalid or has expired"));
        verifyNoInteractions(credentialsRepository, passwordEncoder, sessionRepository);
        verify(tokenRepository, never()).invalidateAll(any(), any());
    }

    // Verify a token whose client has no credentials is rejected rather than half-applied.
    @Test
    void resetPassword_MissingCredentials_Throws() throws Exception {
        when(tokenRepository.consume(eq(sha256("the-token")), any(Instant.class))).thenReturn(Optional.of(clientId));
        when(credentialsRepository.findByClientId(clientId)).thenReturn(Optional.empty());

        assertThrows(InvalidResetTokenException.class,
                () -> service.resetPassword("the-token", "NewPassword123"));

        verify(credentialsRepository, never()).save(any());
        verifyNoInteractions(passwordEncoder, sessionRepository);
    }

    // ==================== configuration ====================

    @Test
    void rejectsNonPositiveTokenLifetime() {
        assertThrows(IllegalArgumentException.class, () -> newService(0, LINK_BASE_URL));
    }

    @Test
    void rejectsBlankLinkBaseUrl() {
        assertThrows(IllegalArgumentException.class, () -> newService(30, " "));
        assertThrows(IllegalArgumentException.class, () -> newService(30, null));
    }
}
