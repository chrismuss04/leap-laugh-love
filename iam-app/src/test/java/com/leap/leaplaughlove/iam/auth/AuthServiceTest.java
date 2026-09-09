package com.leap.leaplaughlove.iam.auth;

import com.leap.leaplaughlove.iam.client.Client;
import com.leap.leaplaughlove.iam.client.ClientCredentials;
import com.leap.leaplaughlove.iam.client.ClientCredentialsRepository;
import com.leap.leaplaughlove.iam.client.ClientRepository;
import com.leap.leaplaughlove.iam.security.JwtService;
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

    @Mock private ClientRepository clientRepository;
    @Mock private ClientCredentialsRepository credentialsRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private JwtService jwtService;

    private AuthService authService;
    private UUID clientId;
    private Client testClient;
    private ClientCredentials testCredentials;

    @BeforeEach
    void setUp() {
        authService = new AuthService(clientRepository, credentialsRepository, passwordEncoder, jwtService);
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
        when(jwtService.generateToken(clientId, "alice@example.com")).thenReturn("mock-jwt-token");
        when(jwtService.getExpirationSeconds()).thenReturn(3600L);

        LoginResponse response = authService.authenticate("alice@example.com", "rawPassword");

        assertNotNull(response);
        assertEquals("mock-jwt-token", response.accessToken());
        assertEquals("Bearer", response.tokenType());
        assertEquals(3600L, response.expiresInSeconds());
        assertEquals(0, testCredentials.getFailedAttempts());
        assertNotNull(testCredentials.getLastLoginAt());
        verify(credentialsRepository).save(testCredentials);
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
        verify(credentialsRepository).save(testCredentials);
    }

    @Test
    void authenticate_5thFailedAttempt_LocksAccount() {
        testCredentials = new ClientCredentials(clientId, "$2a$10$hashedpassword", 4, null);
        when(clientRepository.findByEmail("alice@example.com")).thenReturn(Optional.of(testClient));
        when(credentialsRepository.findByClientId(clientId)).thenReturn(Optional.of(testCredentials));
        when(passwordEncoder.matches("wrongPassword", "$2a$10$hashedpassword")).thenReturn(false);

        assertThrows(InvalidCredentialsException.class,
                () -> authService.authenticate("alice@example.com", "wrongPassword"));

        assertEquals(5, testCredentials.getFailedAttempts());
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
