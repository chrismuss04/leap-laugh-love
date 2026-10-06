package com.leap.leaplaughlove.iam.auth;

import com.leap.leaplaughlove.iam.client.Client;
import com.leap.leaplaughlove.iam.client.ClientCredentials;
import com.leap.leaplaughlove.iam.client.ClientCredentialsRepository;
import com.leap.leaplaughlove.iam.client.ClientRepository;
import com.leap.leaplaughlove.common.security.JwtService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
// Session Timeout & Revocation: align token and stored session lifetimes.
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import com.leap.leaplaughlove.iam.session.ClientSessionRepository;

/**
 * Service for handling authentication logic, including login and account lock management.
 * Provides methods for authenticating clients and managing failed login attempts.
 */
@Service
public class AuthService {

    static final int MAX_FAILED_ATTEMPTS = 3;
    static final String LOCKED_STATUS = "LOCKED";

    private final ClientRepository clientRepository;
    private final ClientCredentialsRepository credentialsRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    // Session Timeout & Revocation
    private final ClientSessionRepository sessionRepository;

    /**
     * Constructs a new AuthService with the specified dependencies.
     * @param clientRepository the repository for managing client entities
     * @param credentialsRepository the repository for managing client credentials
     * @param passwordEncoder the password encoder for verifying client passwords
     * @param jwtService the service for generating JWT tokens
     */
    public AuthService(ClientRepository clientRepository,
                       ClientCredentialsRepository credentialsRepository,
                       PasswordEncoder passwordEncoder,
                       JwtService jwtService,
                       ClientSessionRepository sessionRepository) {
        this.clientRepository = clientRepository;
        this.credentialsRepository = credentialsRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        // Session Timeout & Revocation
        this.sessionRepository = sessionRepository;
    }

    /**
     * Authenticates a client using their email and password. 
     * @param email the email of the client attempting to authenticate
     * @param rawPassword the raw password provided by the client
     * @throws InvalidCredentialsException if the email or password is incorrect
     * @throws AccountLockedException if the account is locked due to too many failed login attempts
     * @return a LoginResponse containing the authentication token and related information
     */
    // Failed logins throw after saving the incremented attempt count and any lock, so those
    // exceptions must not roll the transaction back or the lockout would never persist.
    @Transactional(noRollbackFor = {InvalidCredentialsException.class, AccountLockedException.class})
    public LoginResponse authenticate(String email, String rawPassword) {
        Client client = clientRepository.findByEmail(email)
                .orElseThrow(InvalidCredentialsException::new);

        ClientCredentials creds = credentialsRepository.findByClientId(client.getClientId())
                .orElseThrow(InvalidCredentialsException::new);

        if (LOCKED_STATUS.equalsIgnoreCase(client.getStatus())) {
            throw new AccountLockedException("Account is locked due to too many failed login attempts");
        }

        if (!passwordEncoder.matches(rawPassword, creds.getPasswordHash())) {
            creds.incrementFailedAttempts();
            credentialsRepository.save(creds);
            if (creds.getFailedAttempts() >= MAX_FAILED_ATTEMPTS) {
                client.setStatus(LOCKED_STATUS);
                clientRepository.save(client);
                throw new AccountLockedException("Account is locked due to too many failed login attempts");
            }
            throw new InvalidCredentialsException();
        }

        // commment reset failed attempts on successful login
        creds.resetFailedAttempts();
        creds.setLastLoginAt(OffsetDateTime.now());
        credentialsRepository.save(creds);

        // Session Timeout & Revocation: create a distinct session only after successful authentication.
        // JWT timestamps have second precision; store those exact same times in the database.
        Instant issuedAt = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        long expiresIn = jwtService.getExpirationSeconds();
        Instant expiresAt = issuedAt.plusSeconds(expiresIn);
        UUID sessionId = UUID.randomUUID();
        sessionRepository.create(sessionId, client.getClientId(), issuedAt, expiresAt);
        String token = jwtService.generateToken(client.getClientId(), client.getEmail(),
                sessionId, issuedAt, expiresAt);

        return new LoginResponse(token, "Bearer", expiresIn);
    }
}
