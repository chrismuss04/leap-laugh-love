package com.leap.leaplaughlove.common.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.UUID;

/**
 * Service for generating and validating JSON Web Tokens (JWTs).
 * Provides methods to generate JWT tokens, retrieve their expiration time, and parse and validate them.
 */
@Service
public class JwtService {

    static final String ROLE_CLAIM = "role";

    private final SecretKey signingKey;
    private final long expirationMinutes;

    /**
     * Constructs a new JwtService with the specified secret key and expiration time.
     * @param secret the secret key used for signing JWT tokens
     * @param expirationMinutes the expiration time of JWT tokens in minutes
     */
    public JwtService(@Value("${app.jwt.secret}") String secret,
                       @Value("${app.jwt.expiration-minutes}") long expirationMinutes) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalArgumentException("app.jwt.secret must not be blank");
        }
        if (expirationMinutes <= 0) {
            throw new IllegalArgumentException("app.jwt.expiration-minutes must be > 0");
        }
        this.signingKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expirationMinutes = expirationMinutes;
    }

    /**
     * Generates a JSON Web Token for the given clientId and email.
     * @param clientId
     * @param email
     * @return serialized JWT token as a String
     */
    public String generateToken(UUID clientId, String email) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(clientId.toString())
                .claim("email", email)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(expirationMinutes, ChronoUnit.MINUTES)))
                .signWith(signingKey)
                .compact();
    }

    // Session Timeout & Revocation: use the same timestamps as the persisted login session.
    public String generateToken(UUID clientId, String email, UUID sessionId,
                                Instant issuedAt, Instant expiresAt) {
        return generateToken(clientId, email, Role.CLIENT, sessionId, issuedAt, expiresAt);
    }

    /**
     * Generates a session token for a client or a staff member.
     * @param subjectId the client ID, or the staff member's service_id
     * @param email the signed-in email
     * @param role the role the token grants; its session must be in the matching session table
     * @param sessionId the persisted login session
     * @param issuedAt when the session started
     * @param expiresAt when the session ends
     * @return serialized JWT token as a String
     */
    public String generateToken(UUID subjectId, String email, Role role, UUID sessionId,
                                Instant issuedAt, Instant expiresAt) {
        if (role == null || sessionId == null || !expiresAt.isAfter(issuedAt)) {
            throw new IllegalArgumentException("A role, a session ID and valid expiration are required");
        }
        return Jwts.builder()
                .subject(subjectId.toString())
                .claim("email", email)
                .claim(ROLE_CLAIM, role.name())
                .claim("sid", sessionId.toString())
                .issuedAt(Date.from(issuedAt))
                .expiration(Date.from(expiresAt))
                .signWith(signingKey)
                .compact();
    }

    // Session Timeout & Revocation: background recovery uses short-lived, purpose-scoped tokens.
    public String generateSettlementToken(UUID clientId) {
        return generateServiceToken(clientId, "account-settlement");
    }

    // Session Timeout & Revocation: historical seeding can read prices, but not client APIs.
    public String generateHistoryToken(UUID clientId) {
        return generateServiceToken(clientId, "market-history");
    }

    private String generateServiceToken(UUID clientId, String purpose) {
        Instant now = Instant.now();
        return Jwts.builder().subject(clientId.toString())
                .claim("purpose", purpose)
                .issuedAt(Date.from(now)).expiration(Date.from(now.plusSeconds(60)))
                .signWith(signingKey).compact();
    }

    // Session Timeout & Revocation: parse verified claims once, preserving the session identity.
    // clientId is the token's subject: a client ID, or a staff member's service_id when role is a staff role.
    public record TokenIdentity(UUID clientId, UUID sessionId, Instant expiresAt, String purpose, Role role) {
        /** A client's identity: tokens issued before roles existed, and every client token. */
        public TokenIdentity(UUID clientId, UUID sessionId, Instant expiresAt, String purpose) {
            this(clientId, sessionId, expiresAt, purpose, Role.CLIENT);
        }
    }

    public TokenIdentity parseIdentity(String token) {
        Claims claims = Jwts.parser().verifyWith(signingKey).build()
                .parseSignedClaims(token).getPayload();
        if (claims.getExpiration() == null) {
            throw new IllegalArgumentException("Token expiration is required");
        }
        String sessionId = claims.get("sid", String.class);
        // Tokens issued before roles existed have no role claim; they were all client logins.
        // An unknown role throws IllegalArgumentException, so the request is not authenticated.
        String role = claims.get(ROLE_CLAIM, String.class);
        return new TokenIdentity(UUID.fromString(claims.getSubject()),
                sessionId == null ? null : UUID.fromString(sessionId),
                claims.getExpiration().toInstant(), claims.get("purpose", String.class),
                role == null ? Role.CLIENT : Role.valueOf(role));
    }

    /**
     * Gets the expiration time of the JWT token in seconds.
     * @return expiration time in seconds
     */
    public long getExpirationSeconds() {
        return expirationMinutes * 60;
    }

    /**
     * Parses and validates the given JWT token.
     * @param token the JWT token to parse and validate
     * @return the clientId extracted from the token
     */
    public UUID parseAndValidate(String token) {
        Claims claims = Jwts.parser()
                .verifyWith(signingKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
        return UUID.fromString(claims.getSubject());
    }
}

