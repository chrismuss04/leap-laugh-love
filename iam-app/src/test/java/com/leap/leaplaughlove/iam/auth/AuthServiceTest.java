package com.leap.leaplaughlove.iam.auth;

import com.leap.leaplaughlove.iam.client.Client;
import com.leap.leaplaughlove.iam.client.ClientCredentials;
import com.leap.leaplaughlove.iam.client.ClientCredentialsRepository;
import com.leap.leaplaughlove.iam.client.ClientRepository;
import com.leap.leaplaughlove.common.security.JwtService;
// Session Timeout & Revocation
import com.leap.leaplaughlove.iam.session.ClientSessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    // Increase Test Coverage: authentication must fail closed before issuing a session/token.
    @Test
    void missingCredentialsDoesNotIssueTokenOrCheckPassword() {
        when(clientRepository.findByEmail("alice@example.com")).thenReturn(Optional.of(testClient));
        when(credentialsRepository.findByClientId(clientId)).thenReturn(Optional.empty());

        assertThrows(InvalidCredentialsException.class,
                () -> authService.authenticate("alice@example.com", "password"));

        verifyNoInteractions(passwordEncoder, jwtService, sessionRepository);
        verify(credentialsRepository, never()).save(any());
        verify(clientRepository, never()).save(any());
    }

    @Test
    void successfulLoginClearsPreviousFailuresAndUpdatesLastLogin() {
        var previousLogin = OffsetDateTime.now().minusDays(1);
        testCredentials = new ClientCredentials(clientId, "$2a$10$hashedpassword", 2, previousLogin);
        stubValidPassword();
        when(jwtService.getExpirationSeconds()).thenReturn(3600L);

        authService.authenticate("alice@example.com", "rawPassword");

        assertEquals(0, testCredentials.getFailedAttempts());
        assertTrue(testCredentials.getLastLoginAt().isAfter(previousLogin));
        assertEquals("ACTIVE", testClient.getStatus());
        verify(clientRepository, never()).save(any());
    }

    @Test
    void consecutiveLoginsCreateDistinctSessions() {
        stubValidPassword();
        when(jwtService.getExpirationSeconds()).thenReturn(3600L);

        authService.authenticate("alice@example.com", "rawPassword");
        authService.authenticate("alice@example.com", "rawPassword");

        var ids = org.mockito.ArgumentCaptor.forClass(UUID.class);
        verify(sessionRepository, times(2)).create(ids.capture(), eq(clientId), any(), any());
        assertNotEquals(ids.getAllValues().get(0), ids.getAllValues().get(1));
    }

    @Test
    void sessionStorageFailurePreventsTokenIssuance() {
        stubValidPassword();
        when(jwtService.getExpirationSeconds()).thenReturn(3600L);
        var failure = new org.springframework.dao.DataAccessResourceFailureException("database unavailable");
        doThrow(failure).when(sessionRepository).create(any(), eq(clientId), any(), any());

        assertSame(failure, assertThrows(org.springframework.dao.DataAccessResourceFailureException.class,
                () -> authService.authenticate("alice@example.com", "rawPassword")));

        verify(jwtService, never()).generateToken(any(), any(), any(), any(), any());
        // Transaction rollback is an integration concern; this unit test checks no token escapes.
    }

    @Test
    void credentialSaveFailurePreventsSessionCreation() {
        stubValidPassword();
        var failure = new org.springframework.dao.DataAccessResourceFailureException("database unavailable");
        doThrow(failure).when(credentialsRepository).save(testCredentials);

        assertSame(failure, assertThrows(org.springframework.dao.DataAccessResourceFailureException.class,
                () -> authService.authenticate("alice@example.com", "rawPassword")));
        verifyNoInteractions(sessionRepository, jwtService);
    }

    @Test
    void secondFailedAttemptDoesNotLockOrOverwriteLastLogin() {
        var previousLogin = OffsetDateTime.now().minusDays(1);
        testCredentials = new ClientCredentials(clientId, "$2a$10$hashedpassword", 1, previousLogin);
        when(clientRepository.findByEmail("alice@example.com")).thenReturn(Optional.of(testClient));
        when(credentialsRepository.findByClientId(clientId)).thenReturn(Optional.of(testCredentials));
        when(passwordEncoder.matches("wrong", testCredentials.getPasswordHash())).thenReturn(false);

        assertThrows(InvalidCredentialsException.class,
                () -> authService.authenticate("alice@example.com", "wrong"));

        assertEquals(2, testCredentials.getFailedAttempts());
        assertEquals(previousLogin, testCredentials.getLastLoginAt());
        assertEquals("ACTIVE", testClient.getStatus());
        verify(credentialsRepository).save(testCredentials);
        verify(clientRepository, never()).save(any());
        verifyNoInteractions(sessionRepository, jwtService);
    }

    private void stubValidPassword() {
        when(clientRepository.findByEmail("alice@example.com")).thenReturn(Optional.of(testClient));
        when(credentialsRepository.findByClientId(clientId)).thenReturn(Optional.of(testCredentials));
        when(passwordEncoder.matches("rawPassword", testCredentials.getPasswordHash())).thenReturn(true);
    }

    @Mock private ClientRepository clientRepository;
    @Mock private ClientCredentialsRepository credentialsRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private JwtService jwtService;
    // Session Timeout & Revocation
    @Mock private ClientSessionRepository sessionRepository;

    private AuthService authService;
    private UUID clientId;
    private Client testClient;
    private ClientCredentials testCredentials;

    @BeforeEach
    void setUp() {
        // Session Timeout & Revocation
        authService = new AuthService(clientRepository, credentialsRepository, passwordEncoder, jwtService, sessionRepository);
        clientId = UUID.randomUUID();

        testClient = new Client(
                clientId, "alice@example.com", "555-0100", "ACTIVE", OffsetDateTime.now(),
                "Alice Johnson", LocalDate.of(1990, 1, 1), "123-45-6789",
                "123 Main St", null, "New York", "NY", "10001", "US",
                "INTERMEDIATE", new java.math.BigDecimal("1000.00"));

        testCredentials = new ClientCredentials(clientId, "$2a$10$hashedpassword", 0, null);
    }

    @Test
    void authenticate_Success() {
        when(clientRepository.findByEmail("alice@example.com")).thenReturn(Optional.of(testClient));
        when(credentialsRepository.findByClientId(clientId)).thenReturn(Optional.of(testCredentials));
        when(passwordEncoder.matches("rawPassword", "$2a$10$hashedpassword")).thenReturn(true);
        // Session Timeout & Revocation
        when(jwtService.generateToken(eq(clientId), eq("alice@example.com"), any(UUID.class),
                any(java.time.Instant.class), any(java.time.Instant.class))).thenReturn("mock-jwt-token");
        when(jwtService.getExpirationSeconds()).thenReturn(3600L);

        LoginResponse response = authService.authenticate("alice@example.com", "rawPassword");

        assertNotNull(response);
        assertEquals("mock-jwt-token", response.accessToken());
        assertEquals("Bearer", response.tokenType());
        assertEquals(3600L, response.expiresInSeconds());
        assertEquals(0, testCredentials.getFailedAttempts());
        assertNotNull(testCredentials.getLastLoginAt());
        verify(credentialsRepository).save(testCredentials);
        // Session Timeout & Revocation: the persisted session and signed token share ID and expiry.
        var id = org.mockito.ArgumentCaptor.forClass(UUID.class);
        var start = org.mockito.ArgumentCaptor.forClass(java.time.Instant.class);
        var end = org.mockito.ArgumentCaptor.forClass(java.time.Instant.class);
        verify(sessionRepository).create(id.capture(), eq(clientId), start.capture(), end.capture());
        assertEquals(3600L, java.time.Duration.between(start.getValue(), end.getValue()).getSeconds());
        verify(jwtService).generateToken(clientId, "alice@example.com",
                id.getValue(), start.getValue(), end.getValue());
    }

    @Test
    void authenticate_UnknownEmail_ThrowsInvalidCredentials() {
        when(clientRepository.findByEmail("unknown@example.com")).thenReturn(Optional.empty());

        assertThrows(InvalidCredentialsException.class,
                () -> authService.authenticate("unknown@example.com", "password"));
    }

    @Test
    void authenticate_WrongPassword_IncrementsFailedAttempts() {
        when(clientRepository.findByEmail("alice@example.com")).thenReturn(Optional.of(testClient));
        when(credentialsRepository.findByClientId(clientId)).thenReturn(Optional.of(testCredentials));
        when(passwordEncoder.matches("wrongPassword", "$2a$10$hashedpassword")).thenReturn(false);

        assertThrows(InvalidCredentialsException.class,
                () -> authService.authenticate("alice@example.com", "wrongPassword"));

        assertEquals(1, testCredentials.getFailedAttempts());
        // Session Timeout & Revocation: failed authentication must not create a session.
        verifyNoInteractions(sessionRepository);
        verify(credentialsRepository).save(testCredentials);
    }

    @Test
    void authenticate_3rdFailedAttempt_LocksAccount() {
        testCredentials = new ClientCredentials(clientId, "$2a$10$hashedpassword", 2, null);
        when(clientRepository.findByEmail("alice@example.com")).thenReturn(Optional.of(testClient));
        when(credentialsRepository.findByClientId(clientId)).thenReturn(Optional.of(testCredentials));
        when(passwordEncoder.matches("wrongPassword", "$2a$10$hashedpassword")).thenReturn(false);

        AccountLockedException ex = assertThrows(AccountLockedException.class,
                () -> authService.authenticate("alice@example.com", "wrongPassword"));

        assertTrue(ex.getMessage().contains("locked"));
        assertEquals(3, testCredentials.getFailedAttempts());
        assertEquals("LOCKED", testClient.getStatus());
        verify(clientRepository).save(testClient);
        verify(credentialsRepository).save(testCredentials);
    }

    @Test
    void authenticate_LockedAccount_ThrowsAccountLockedException() {
        testClient.setStatus("LOCKED");
        when(clientRepository.findByEmail("alice@example.com")).thenReturn(Optional.of(testClient));
        when(credentialsRepository.findByClientId(clientId)).thenReturn(Optional.of(testCredentials));

        AccountLockedException ex = assertThrows(AccountLockedException.class,
                () -> authService.authenticate("alice@example.com", "rawPassword"));

        assertTrue(ex.getMessage().contains("locked"));
        verify(passwordEncoder, never()).matches(anyString(), anyString());
    }
}
