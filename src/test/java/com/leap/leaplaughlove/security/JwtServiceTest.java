package com.leap.leaplaughlove.security;

import io.jsonwebtoken.ExpiredJwtException;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JwtServiceTest {

    private final JwtService jwtService = new JwtService(
            "unit-test-secret-key-that-is-long-enough-for-hs256-signing", 60);

    @Test
    void generatesTokenAndParsesSameClientId() {
        UUID clientId = UUID.randomUUID();

        String token = jwtService.generateToken(clientId, "client@example.com");
        UUID parsed = jwtService.parseAndValidate(token);

        assertEquals(clientId, parsed);
    }

    @Test
    void rejectsExpiredToken() {
        JwtService shortLived = new JwtService(
                "unit-test-secret-key-that-is-long-enough-for-hs256-signing", 0);
        UUID clientId = UUID.randomUUID();
        String token = shortLived.generateToken(clientId, "client@example.com");

        assertThrows(ExpiredJwtException.class, () -> shortLived.parseAndValidate(token));
    }
}
