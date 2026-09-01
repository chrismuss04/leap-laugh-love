package com.leap.leaplaughlove.auth;

import com.leap.leaplaughlove.iam.Client;
import com.leap.leaplaughlove.iam.ClientCredentials;
import com.leap.leaplaughlove.iam.ClientCredentialsRepository;
import com.leap.leaplaughlove.iam.ClientRepository;
import com.leap.leaplaughlove.security.JwtService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;

@Service
public class AuthService {

    private static final int MAX_FAILED_ATTEMPTS = 5;
    private static final long LOCK_DURATION_MINUTES = 15;

    private final ClientRepository clientRepository;
    private final ClientCredentialsRepository credentialsRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public AuthService(ClientRepository clientRepository,
                        ClientCredentialsRepository credentialsRepository,
                        PasswordEncoder passwordEncoder,
                        JwtService jwtService) {
        this.clientRepository = clientRepository;
        this.credentialsRepository = credentialsRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    @Transactional
    public LoginResponse login(String email, String rawPassword) {
        Client client = clientRepository.findByEmailIgnoreCase(email)
                .filter(c -> "ACTIVE".equals(c.getStatus()))
                .orElseThrow(() -> new InvalidCredentialsException("Invalid email or password"));

        ClientCredentials credentials = credentialsRepository.findById(client.getClientId())
                .orElseThrow(() -> new InvalidCredentialsException("Invalid email or password"));

        OffsetDateTime now = OffsetDateTime.now();
        if (credentials.getLockedUntil() != null && credentials.getLockedUntil().isAfter(now)) {
            throw new AccountLockedException(credentials.getLockedUntil());
        }

        if (!passwordEncoder.matches(rawPassword, credentials.getPasswordHash())) {
            registerFailedAttempt(credentials, now);
            throw new InvalidCredentialsException("Invalid email or password");
        }

        credentials.setFailedSignInAttempts(0);
        credentials.setLockedUntil(null);
        credentialsRepository.save(credentials);

        String token = jwtService.generateToken(client.getClientId(), client.getEmail());
        return new LoginResponse(token, "Bearer", jwtService.getExpirationSeconds());
    }

    private void registerFailedAttempt(ClientCredentials credentials, OffsetDateTime now) {
        int attempts = credentials.getFailedSignInAttempts() + 1;
        credentials.setFailedSignInAttempts(attempts);
        if (attempts >= MAX_FAILED_ATTEMPTS) {
            credentials.setLockedUntil(now.plusMinutes(LOCK_DURATION_MINUTES));
        }
        credentialsRepository.save(credentials);
    }
}
