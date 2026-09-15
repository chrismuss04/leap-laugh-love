package com.leap.leaplaughlove.common.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class JwtServiceTest {

    private static final String SECRET = "thisisasecretkeyforjwtsigningandvalidation123456";
    private JwtService jwtService;

    @BeforeEach
    void setUp() {
        jwtService = new JwtService(SECRET, 60);
    }

    @Test
    void testGenerateToken() {
        UUID clientId = UUID.randomUUID();
        String email = "test@example.com";
        String token = jwtService.generateToken(clientId, email);

        assertNotNull(token);
        assertFalse(token.isBlank());
    }

    @Test
    void testParseAndValidate() {
        UUID clientId = UUID.randomUUID();
        String email = "test@example.com";
        String token = jwtService.generateToken(clientId, email);

        UUID parsedClientId = jwtService.parseAndValidate(token);
        assertEquals(clientId, parsedClientId);
    }

    @Test
    void testGetExpirationSeconds() {
        assertEquals(3600, jwtService.getExpirationSeconds());
    }

    @Test
    void testExpiredTokenThrows() throws InterruptedException {
        JwtService shortLivedService = new JwtService(SECRET, 1);
        UUID clientId = UUID.randomUUID();
        String email = "test@example.com";
        String token = shortLivedService.generateToken(clientId, email);

        assertNotNull(shortLivedService.parseAndValidate(token));
    }

    @Test
    void testInvalidTokenThrows() {
        assertThrows(Exception.class, () -> jwtService.parseAndValidate("invalid.token.here"));
    }

    @Test
    void testBlankSecretThrows() {
        assertThrows(IllegalArgumentException.class, () -> new JwtService("", 60));
        assertThrows(IllegalArgumentException.class, () -> new JwtService(null, 60));
    }

    @Test
    void testInvalidExpirationThrows() {
        assertThrows(IllegalArgumentException.class, () -> new JwtService(SECRET, 0));
        assertThrows(IllegalArgumentException.class, () -> new JwtService(SECRET, -1));
    }
}

