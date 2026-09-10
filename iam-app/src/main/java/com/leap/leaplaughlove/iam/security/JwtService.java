package com.leap.leaplaughlove.iam.security;

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

    private final SecretKey signingKey;
    private final long expirationMinutes;

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
