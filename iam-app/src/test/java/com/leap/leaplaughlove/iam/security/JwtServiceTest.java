package com.leap.leaplaughlove.iam.security;

import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class JwtServiceTest {

    private static final String SECRET = "thisisaverylongsecretkeyfortestingpurposes1234567890";
    private JwtService jwtService;

    @BeforeEach
    void setUp() {
        jwtService = new JwtService(SECRET, 60);
    }

    @Test
    void generateToken_And_ParseAndValidate_Success() {
        UUID clientId = UUID.randomUUID();
        String token = jwtService.generateToken(clientId, "test@example.com");

        assertNotNull(token);
        assertFalse(token.isBlank());

        UUID parsedId = jwtService.parseAndValidate(token);
        assertEquals(clientId, parsedId);
    }

    @Test
    void parseAndValidate_TamperedToken_ThrowsException() {
        UUID clientId = UUID.randomUUID();
        String token = jwtService.generateToken(clientId, "test@example.com");
        String tamperedToken = token + "bad";

        assertThrows(JwtException.class, () -> jwtService.parseAndValidate(tamperedToken));
    }

    @Test
    void parseAndValidate_ExpiredToken_ThrowsExpiredJwtException() {
        JwtService shortLivedService = new JwtService(SECRET, 1);
        UUID clientId = UUID.randomUUID();
        String token = shortLivedService.generateToken(clientId, "test@example.com");

        assertNotNull(token);
    }

    @Test
    void getExpirationSeconds_ReturnsCorrectValue() {
        assertEquals(3600L, jwtService.getExpirationSeconds());
    }

    @Test
    void constructor_BlankSecret_ThrowsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class, () -> new JwtService("", 60));
    }

    @Test
    void constructor_ZeroExpiration_ThrowsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class, () -> new JwtService(SECRET, 0));
    }
}
