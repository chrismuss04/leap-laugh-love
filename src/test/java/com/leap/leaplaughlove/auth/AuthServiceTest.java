package com.leap.leaplaughlove.auth;

import com.leap.leaplaughlove.iam.Client;
import com.leap.leaplaughlove.iam.ClientCredentials;
import com.leap.leaplaughlove.iam.ClientCredentialsRepository;
import com.leap.leaplaughlove.iam.ClientRepository;
import com.leap.leaplaughlove.security.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.lang.reflect.Field;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private ClientRepository clientRepository;
    @Mock
    private ClientCredentialsRepository credentialsRepository;
    @Mock
    private PasswordEncoder passwordEncoder;

    private final JwtService jwtService = new JwtService(
            "unit-test-secret-key-that-is-long-enough-for-hs256-signing", 60);

    private AuthService authService;

    private UUID clientId;
    private Client client;
    private ClientCredentials credentials;

    @BeforeEach
    void setUp() throws Exception {
        authService = new AuthService(clientRepository, credentialsRepository, passwordEncoder, jwtService);

        clientId = UUID.randomUUID();
        client = newClient(clientId, "client@example.com", "ACTIVE");
        credentials = newCredentials(clientId, "hashed-password", 0, null);
    }

    @Test
    void loginSucceedsAndIssuesToken() {
        when(clientRepository.findByEmailIgnoreCase("client@example.com")).thenReturn(Optional.of(client));
        when(credentialsRepository.findById(clientId)).thenReturn(Optional.of(credentials));
        when(passwordEncoder.matches("correct-password", "hashed-password")).thenReturn(true);

        LoginResponse response = authService.login("client@example.com", "correct-password");

        assertEquals(clientId, jwtService.parseAndValidate(response.accessToken()));
        assertEquals(3600L, response.expiresInSeconds());
        verify(credentialsRepository).save(credentials);
    }

    @Test
    void loginFailsWithWrongPasswordAndIncrementsFailedAttempts() {
        when(clientRepository.findByEmailIgnoreCase("client@example.com")).thenReturn(Optional.of(client));
        when(credentialsRepository.findById(clientId)).thenReturn(Optional.of(credentials));
        when(passwordEncoder.matches("wrong-password", "hashed-password")).thenReturn(false);

        assertThrows(InvalidCredentialsException.class,
                () -> authService.login("client@example.com", "wrong-password"));

        assertEquals(1, credentials.getFailedSignInAttempts());
        verify(credentialsRepository).save(credentials);
    }

    @Test
    void loginLocksAccountAfterMaxFailedAttempts() {
        ClientCredentials nearlyLocked = newCredentials(clientId, "hashed-password", 4, null);
        when(clientRepository.findByEmailIgnoreCase("client@example.com")).thenReturn(Optional.of(client));
        when(credentialsRepository.findById(clientId)).thenReturn(Optional.of(nearlyLocked));
        when(passwordEncoder.matches(any(), any())).thenReturn(false);

        assertThrows(InvalidCredentialsException.class,
                () -> authService.login("client@example.com", "wrong-password"));

        assertEquals(5, nearlyLocked.getFailedSignInAttempts());
        assertEquals(true, nearlyLocked.getLockedUntil().isAfter(OffsetDateTime.now()));
    }

    @Test
    void loginRejectsWhenAccountAlreadyLocked() {
        ClientCredentials locked = newCredentials(clientId, "hashed-password", 5, OffsetDateTime.now().plusMinutes(10));
        when(clientRepository.findByEmailIgnoreCase("client@example.com")).thenReturn(Optional.of(client));
        when(credentialsRepository.findById(clientId)).thenReturn(Optional.of(locked));

        assertThrows(AccountLockedException.class,
                () -> authService.login("client@example.com", "any-password"));
    }

    @Test
    void loginRejectsUnknownEmail() {
        when(clientRepository.findByEmailIgnoreCase("missing@example.com")).thenReturn(Optional.empty());

        assertThrows(InvalidCredentialsException.class,
                () -> authService.login("missing@example.com", "any-password"));
    }

    private static Client newClient(UUID id, String email, String status) throws Exception {
        Client c = instantiate(Client.class);
        setField(c, "clientId", id);
        setField(c, "email", email);
        setField(c, "status", status);
        return c;
    }

    private static ClientCredentials newCredentials(UUID id, String hash, int failedAttempts, OffsetDateTime lockedUntil) {
        try {
            ClientCredentials creds = instantiate(ClientCredentials.class);
            setField(creds, "clientId", id);
            setField(creds, "passwordHash", hash);
            creds.setFailedSignInAttempts(failedAttempts);
            creds.setLockedUntil(lockedUntil);
            return creds;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static <T> T instantiate(Class<T> type) throws Exception {
        var constructor = type.getDeclaredConstructor();
        constructor.setAccessible(true);
        return constructor.newInstance();
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
