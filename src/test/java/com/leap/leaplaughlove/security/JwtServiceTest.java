package com.leap.leaplaughlove.security;

import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JwtServiceTest {

    private static final String SECRET = "unit-test-secret-key-that-is-long-enough-for-hs256-signing";

    private final JwtService jwtService = new JwtService(SECRET, 60);

    @Test
    void generatesTokenAndParsesSameClientId() {
        UUID clientId = UUID.randomUUID();

        String token = jwtService.generateToken(clientId, "client@example.com");
        UUID parsed = jwtService.parseAndValidate(token);

        assertEquals(clientId, parsed);
    }

    @Test
    void rejectsExpiredToken() {
        // Build an already-expired token directly (expiration-minutes must be > 0 on JwtService itself).
        SecretKey signingKey = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
        Instant past = Instant.now().minus(1, ChronoUnit.HOURS);
        String expiredToken = Jwts.builder()
                .subject(UUID.randomUUID().toString())
                .issuedAt(Date.from(past.minus(1, ChronoUnit.MINUTES)))
                .expiration(Date.from(past))
                .signWith(signingKey)
                .compact();

        assertThrows(ExpiredJwtException.class, () -> jwtService.parseAndValidate(expiredToken));
    }

    @Test
    void rejectsBlankSecret() {
        assertThrows(IllegalArgumentException.class, () -> new JwtService(" ", 60));
    }

    @Test
    void rejectsNonPositiveExpiration() {
        assertThrows(IllegalArgumentException.class, () -> new JwtService(SECRET, 0));
    }
}
