package com.leap.leaplaughlove.iam.auth;

import com.leap.leaplaughlove.iam.client.Client;
import com.leap.leaplaughlove.iam.client.ClientCredentials;
import com.leap.leaplaughlove.iam.client.ClientCredentialsRepository;
import com.leap.leaplaughlove.iam.client.ClientRepository;
import com.leap.leaplaughlove.iam.security.JwtService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;

/**
 * Service for handling authentication logic, including login and account lock management.
 * Provides methods for authenticating clients and managing failed login attempts.
 */
@Service
public class AuthService {

    private static final int MAX_FAILED_ATTEMPTS = 5;
    private static final String LOCKED_STATUS = "LOCKED";

    private final ClientRepository clientRepository;
    private final ClientCredentialsRepository credentialsRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

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
                       JwtService jwtService) {
        this.clientRepository = clientRepository;
        this.credentialsRepository = credentialsRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    /**
     * Authenticates a client using their email and password. 
     * @param email the email of the client attempting to authenticate
     * @param rawPassword the raw password provided by the client
     * @throws InvalidCredentialsException if the email or password is incorrect
     * @throws AccountLockedException if the account is locked due to too many failed login attempts
     * @return a LoginResponse containing the authentication token and related information
     */
    @Transactional
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
            if (creds.getFailedAttempts() >= MAX_FAILED_ATTEMPTS) {
                client.setStatus(LOCKED_STATUS);
                clientRepository.save(client);
            }
            credentialsRepository.save(creds);
            throw new InvalidCredentialsException();
        }

        // commment reset failed attempts on successful login
        creds.resetFailedAttempts();
        creds.setLastLoginAt(OffsetDateTime.now());
        credentialsRepository.save(creds);

        String token = jwtService.generateToken(client.getClientId(), client.getEmail());
        long expiresIn = jwtService.getExpirationSeconds();

        return new LoginResponse(token, "Bearer", expiresIn);
    }
}
