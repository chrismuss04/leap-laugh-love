package com.leap.leaplaughlove.iam.auth;

import com.leap.leaplaughlove.iam.client.Client;
import com.leap.leaplaughlove.iam.client.ClientCredentials;
import com.leap.leaplaughlove.iam.client.ClientCredentialsRepository;
import com.leap.leaplaughlove.iam.client.ClientRepository;
import com.leap.leaplaughlove.common.security.JwtService;
// Session Timeout & Revocation
import com.leap.leaplaughlove.iam.session.ClientSessionRepository;
import com.leap.leaplaughlove.common.security.Role;
import com.leap.leaplaughlove.iam.session.StaffSessionRepository;
import com.leap.leaplaughlove.iam.staff.StaffCredentialsRepository;
import com.leap.leaplaughlove.iam.staff.StaffCredentialsRepository.StaffCredentials;
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

    // Verify missing credentials prevent password checks, session creation, and token issuance.
    @Test
    void rejectsMissingCredentials() {
        when(clientRepository.findByEmail("alice@example.com")).thenReturn(Optional.of(testClient));
        when(credentialsRepository.findByClientId(clientId)).thenReturn(Optional.empty());

        assertThrows(InvalidCredentialsException.class,
                () -> authService.authenticate("alice@example.com", "password"));

        verifyNoInteractions(passwordEncoder, jwtService, sessionRepository);
        verify(credentialsRepository, never()).save(any());
        verify(clientRepository, never()).save(any());
    }

    // Verify successful login clears failed attempts and refreshes the last-login timestamp.
    @Test
    void resetsLoginFailures() {
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

    // Verify each successful login receives its own session ID.
    @Test
    void createsDistinctSessions() {
        stubValidPassword();
        when(jwtService.getExpirationSeconds()).thenReturn(3600L);

        authService.authenticate("alice@example.com", "rawPassword");
        authService.authenticate("alice@example.com", "rawPassword");

        var ids = org.mockito.ArgumentCaptor.forClass(UUID.class);
        verify(sessionRepository, times(2)).create(ids.capture(), eq(clientId), any(), any());
        assertNotEquals(ids.getAllValues().get(0), ids.getAllValues().get(1));
    }

    // Verify a session-storage failure prevents token issuance.
    @Test
    void sessionFailureBlocksToken() {
        stubValidPassword();
        when(jwtService.getExpirationSeconds()).thenReturn(3600L);
        var failure = new org.springframework.dao.DataAccessResourceFailureException("database unavailable");
        doThrow(failure).when(sessionRepository).create(any(), eq(clientId), any(), any());

        assertSame(failure, assertThrows(org.springframework.dao.DataAccessResourceFailureException.class,
                () -> authService.authenticate("alice@example.com", "rawPassword")));

        verify(jwtService, never()).generateToken(any(), any(), any(), any(), any());
        // Transaction rollback is an integration concern; this unit test checks no token escapes.
    }

    // Verify a credential-save failure prevents session creation and token issuance.
    @Test
    void credentialFailureBlocksLogin() {
        stubValidPassword();
        var failure = new org.springframework.dao.DataAccessResourceFailureException("database unavailable");
        doThrow(failure).when(credentialsRepository).save(testCredentials);

        assertSame(failure, assertThrows(org.springframework.dao.DataAccessResourceFailureException.class,
                () -> authService.authenticate("alice@example.com", "rawPassword")));
        verifyNoInteractions(sessionRepository, jwtService);
    }

    // Verify the second failed attempt preserves the last login and leaves the account unlocked.
    @Test
    void secondFailureDoesNotLock() {
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
    // Analyst login
    @Mock private StaffCredentialsRepository staffCredentialsRepository;
    @Mock private StaffSessionRepository staffSessionRepository;

    private AuthService authService;
    private UUID clientId;
    private Client testClient;
    private ClientCredentials testCredentials;

    @BeforeEach
    void setUp() {
        // Session Timeout & Revocation
        authService = new AuthService(clientRepository, credentialsRepository, passwordEncoder, jwtService, sessionRepository,
                staffCredentialsRepository, staffSessionRepository);
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
        when(staffCredentialsRepository.findByEmail("unknown@example.com")).thenReturn(Optional.empty());

        assertThrows(InvalidCredentialsException.class,
                () -> authService.authenticate("unknown@example.com", "password"));
        verifyNoInteractions(passwordEncoder, jwtService, sessionRepository, staffSessionRepository);
    }

    // Analyst login: an email that isn't a client's signs in as staff, with a staff session and role.
    @Test
    void staffLoginIssuesStaffToken() {
        stubStaff("ACTIVE", 2);
        when(passwordEncoder.matches("rawPassword", "$2a$10$staffhash")).thenReturn(true);
        when(jwtService.getExpirationSeconds()).thenReturn(3600L);
        when(jwtService.generateToken(eq(staffId), eq("analyst@leap.com"), eq(Role.COMMERCIAL_ANALYST),
                any(UUID.class), any(java.time.Instant.class), any(java.time.Instant.class))).thenReturn("staff-token");

        LoginResponse response = authService.authenticate("analyst@leap.com", "rawPassword");

        assertEquals("staff-token", response.accessToken());
        assertEquals(Role.COMMERCIAL_ANALYST, response.role());
        assertEquals(3600L, response.expiresInSeconds());
        var id = org.mockito.ArgumentCaptor.forClass(UUID.class);
        var start = org.mockito.ArgumentCaptor.forClass(java.time.Instant.class);
        var end = org.mockito.ArgumentCaptor.forClass(java.time.Instant.class);
        verify(staffSessionRepository).create(id.capture(), eq(staffId), start.capture(), end.capture());
        verify(staffCredentialsRepository).recordSuccessfulLogin(staffId, start.getValue());
        verify(jwtService).generateToken(staffId, "analyst@leap.com", Role.COMMERCIAL_ANALYST,
                id.getValue(), start.getValue(), end.getValue());
        // A staff login never touches client credentials or client sessions.
        verifyNoInteractions(credentialsRepository, sessionRepository);
    }

    // Analyst login: trading operations staff sign in the same way, with their own role.
    @Test
    void tradingOperationsLoginKeepsItsRole() {
        when(clientRepository.findByEmail("ops@leap.com")).thenReturn(Optional.empty());
        when(staffCredentialsRepository.findByEmail("ops@leap.com")).thenReturn(Optional.of(new StaffCredentials(
                staffId, "ops@leap.com", "$2a$10$staffhash", Role.TRADING_OPERATIONS, "ACTIVE", 0)));
        when(passwordEncoder.matches("rawPassword", "$2a$10$staffhash")).thenReturn(true);
        when(jwtService.getExpirationSeconds()).thenReturn(3600L);

        assertEquals(Role.TRADING_OPERATIONS, authService.authenticate("ops@leap.com", "rawPassword").role());
        verify(jwtService).generateToken(eq(staffId), eq("ops@leap.com"), eq(Role.TRADING_OPERATIONS),
                any(), any(), any());
    }

    @Test
    void staffWrongPasswordCountsAttempt() {
        stubStaff("ACTIVE", 0);
        when(passwordEncoder.matches("wrong", "$2a$10$staffhash")).thenReturn(false);
        when(staffCredentialsRepository.recordFailedAttempt(staffId, 3)).thenReturn(1);

        assertThrows(InvalidCredentialsException.class, () -> authService.authenticate("analyst@leap.com", "wrong"));

        verify(staffCredentialsRepository, never()).recordSuccessfulLogin(any(), any());
        verifyNoInteractions(jwtService, staffSessionRepository);
    }

    @Test
    void staffThirdFailureLocks() {
        stubStaff("ACTIVE", 2);
        when(passwordEncoder.matches("wrong", "$2a$10$staffhash")).thenReturn(false);
        when(staffCredentialsRepository.recordFailedAttempt(staffId, 3)).thenReturn(3);

        assertThrows(AccountLockedException.class, () -> authService.authenticate("analyst@leap.com", "wrong"));
        verifyNoInteractions(jwtService, staffSessionRepository);
    }

    @Test
    void lockedStaffCannotSignIn() {
        stubStaff("LOCKED", 3);

        assertThrows(AccountLockedException.class,
                () -> authService.authenticate("analyst@leap.com", "rawPassword"));
        verifyNoInteractions(passwordEncoder, jwtService, staffSessionRepository);
    }

    // A pending or deleted staff account is refused without saying why.
    @Test
    void inactiveStaffCannotSignIn() {
        stubStaff("DELETED", 0);

        assertThrows(InvalidCredentialsException.class,
                () -> authService.authenticate("analyst@leap.com", "rawPassword"));
        verifyNoInteractions(passwordEncoder, jwtService, staffSessionRepository);
    }

    // A client's email always signs in as that client, never as staff.
    @Test
    void clientEmailNeverChecksStaff() {
        stubValidPassword();
        when(jwtService.getExpirationSeconds()).thenReturn(3600L);

        assertEquals(Role.CLIENT, authService.authenticate("alice@example.com", "rawPassword").role());
        verifyNoInteractions(staffCredentialsRepository, staffSessionRepository);
    }

    private final UUID staffId = UUID.randomUUID();

    private void stubStaff(String status, int failedAttempts) {
        when(clientRepository.findByEmail("analyst@leap.com")).thenReturn(Optional.empty());
        when(staffCredentialsRepository.findByEmail("analyst@leap.com")).thenReturn(Optional.of(new StaffCredentials(
                staffId, "analyst@leap.com", "$2a$10$staffhash", Role.COMMERCIAL_ANALYST, status, failedAttempts)));
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
