package com.leap.leaplaughlove.iam.auth;

import com.leap.leaplaughlove.iam.client.Client;
import com.leap.leaplaughlove.iam.client.ClientCredentials;
import com.leap.leaplaughlove.iam.client.ClientCredentialsRepository;
import com.leap.leaplaughlove.iam.client.ClientRepository;
import com.leap.leaplaughlove.iam.session.ClientSessionRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Service for password recovery: issues the single-use link a client is emailed, and changes the
 * password when that link is used.
 */
@Service
public class PasswordResetService {

    private static final String ACTIVE_STATUS = "ACTIVE";
    // A locked client is the one most likely to need a reset; deleted and pending clients get no link.
    private static final Set<String> RESETTABLE_STATUSES = Set.of(ACTIVE_STATUS, AuthService.LOCKED_STATUS);
    private static final int TOKEN_BYTES = 32;

    private final ClientRepository clientRepository;
    private final ClientCredentialsRepository credentialsRepository;
    private final PasswordResetTokenRepository tokenRepository;
    private final ClientSessionRepository sessionRepository;
    private final PasswordEncoder passwordEncoder;
    private final ApplicationEventPublisher eventPublisher;
    private final Duration tokenTtl;
    private final String linkBaseUrl;
    private final SecureRandom random = new SecureRandom();

    /**
     * Constructs a new PasswordResetService with the specified dependencies.
     * @param clientRepository the repository for managing client entities
     * @param credentialsRepository the repository for managing client credentials
     * @param tokenRepository the repository storing the hash of each issued reset token
     * @param sessionRepository the repository used to sign the client out once the password changes
     * @param passwordEncoder the password encoder for hashing the new password
     * @param eventPublisher publishes the reset link so it is delivered once the token has committed
     * @param tokenTtlMinutes how long a reset link stays usable, in minutes
     * @param linkBaseUrl the frontend page the link opens; the token is appended as a query parameter
     */
    public PasswordResetService(ClientRepository clientRepository,
                                ClientCredentialsRepository credentialsRepository,
                                PasswordResetTokenRepository tokenRepository,
                                ClientSessionRepository sessionRepository,
                                PasswordEncoder passwordEncoder,
                                ApplicationEventPublisher eventPublisher,
                                @Value("${app.password-reset.token-ttl-minutes}") long tokenTtlMinutes,
                                @Value("${app.password-reset.link-base-url}") String linkBaseUrl) {
        if (tokenTtlMinutes <= 0) {
            throw new IllegalArgumentException("app.password-reset.token-ttl-minutes must be > 0");
        }
        if (linkBaseUrl == null || linkBaseUrl.isBlank()) {
            throw new IllegalArgumentException("app.password-reset.link-base-url must not be blank");
        }
        this.clientRepository = clientRepository;
        this.credentialsRepository = credentialsRepository;
        this.tokenRepository = tokenRepository;
        this.sessionRepository = sessionRepository;
        this.passwordEncoder = passwordEncoder;
        this.eventPublisher = eventPublisher;
        this.tokenTtl = Duration.ofMinutes(tokenTtlMinutes);
        this.linkBaseUrl = linkBaseUrl;
    }

    /**
     * Issues a reset link for the client with this email, replacing any earlier link. Does nothing
     * for an email that has no resettable client, and never tells the caller which case it was, so
     * this cannot be used to find out which emails are registered.
     * @param email the email address the reset was requested for
     */
    @Transactional
    public void requestReset(String email) {
        Optional<Client> match = clientRepository.findByEmail(email)
                .filter(client -> RESETTABLE_STATUSES.contains(client.getStatus()));
        if (match.isEmpty()) {
            return;
        }
        Client client = match.get();

        Instant now = Instant.now();
        Instant expiresAt = now.plus(tokenTtl);
        String token = newToken();
        tokenRepository.invalidateAll(client.getClientId(), now);
        tokenRepository.create(UUID.randomUUID(), client.getClientId(), hash(token), now, expiresAt);

        eventPublisher.publishEvent(new PasswordResetRequestedEvent(
                client.getEmail(), linkBaseUrl + "?token=" + token, expiresAt));
    }

    /**
     * Checks that a reset link can still be used, without spending it.
     * @param token the token from the emailed reset link
     * @throws InvalidResetTokenException if the token is unknown, expired, already used or replaced
     */
    public void validateToken(String token) {
        if (!tokenRepository.isUsable(hash(token), Instant.now())) {
            throw new InvalidResetTokenException();
        }
    }

    /**
     * Sets a new password using the token from a reset link. The link is spent, the client's failed
     * login attempts are cleared along with any lock they caused, and every existing session ends.
     * @param token the token from the emailed reset link
     * @param newPassword the raw password to set
     * @throws InvalidResetTokenException if the token is unknown, expired or already used
     */
    @Transactional
    public void resetPassword(String token, String newPassword) {
        Instant now = Instant.now();
        UUID clientId = tokenRepository.consume(hash(token), now)
                .orElseThrow(InvalidResetTokenException::new);
        ClientCredentials creds = credentialsRepository.findByClientId(clientId)
                .orElseThrow(InvalidResetTokenException::new);

        // Only a lock caused by failed logins is lifted; a client locked for any other reason stays locked.
        if (creds.getFailedAttempts() >= AuthService.MAX_FAILED_ATTEMPTS) {
            clientRepository.findById(clientId)
                    .filter(client -> AuthService.LOCKED_STATUS.equalsIgnoreCase(client.getStatus()))
                    .ifPresent(client -> {
                        client.setStatus(ACTIVE_STATUS);
                        clientRepository.save(client);
                    });
        }

        creds.setPasswordHash(passwordEncoder.encode(newPassword));
        creds.resetFailedAttempts();
        credentialsRepository.save(creds);

        tokenRepository.invalidateAll(clientId, now);
        sessionRepository.revokeAll(clientId, now);
    }

    private String newToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    // The token is long and random, so a fast unsalted hash is enough to keep the stored value unusable.
    private static String hash(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
