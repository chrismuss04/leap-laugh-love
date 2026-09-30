package com.leap.leaplaughlove.common.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class JwtServiceTest {

    // Session Timeout & Revocation: new claims must survive signature validation.
    @Test
    void parsesSessionAndRestrictedSettlementIdentities() {
        UUID client = UUID.randomUUID();
        UUID session = UUID.randomUUID();
        var now = java.time.Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        var identity = jwtService.parseIdentity(jwtService.generateToken(
                client, "client@example.com", session, now, now.plusSeconds(3600)));
        assertEquals(client, identity.clientId());
        assertEquals(session, identity.sessionId());
        assertEquals(now.plusSeconds(3600), identity.expiresAt());
        assertNull(identity.purpose());
        var internal = jwtService.parseIdentity(jwtService.generateSettlementToken(client));
        assertEquals("account-settlement", internal.purpose());
        assertNull(internal.sessionId());
    }

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

