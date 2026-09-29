// Session Timeout & Revocation: deterministic boundary tests against a real SQL session store.
package com.leap.leaplaughlove.common.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.time.*;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class ClientSessionValidatorTest {
    private final Instant now = Instant.parse("2026-09-29T12:00:00Z");
    private final UUID sid = UUID.randomUUID();
    private final UUID client = UUID.randomUUID();
    private JdbcTemplate jdbc;
    private ClientSessionValidator validator;

    @BeforeEach
    void setup() {
        jdbc = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", ""));
        jdbc.execute("CREATE SCHEMA iam");
        jdbc.execute("CREATE TABLE iam.client_sessions (session_id UUID PRIMARY KEY, client_id UUID, "
                + "last_activity_at TIMESTAMP WITH TIME ZONE, expires_at TIMESTAMP WITH TIME ZONE, "
                + "revoked_at TIMESTAMP WITH TIME ZONE)");
        jdbc.update("INSERT INTO iam.client_sessions VALUES (?, ?, ?, ?, NULL)",
                sid, client, now.atOffset(ZoneOffset.UTC), now.plusSeconds(3600).atOffset(ZoneOffset.UTC));
        validator = new ClientSessionValidator(jdbc, Clock.fixed(now, ZoneOffset.UTC));
    }

    private boolean active() { return validator.isActive(sid, client, now.plusSeconds(3600)); }

    @Test void acceptsActiveSessionWithoutChangingActivity() {
        jdbc.update("UPDATE iam.client_sessions SET last_activity_at = ?", now.minusSeconds(599).atOffset(ZoneOffset.UTC));
        assertTrue(active());
        assertTrue(active());
        assertEquals(now.minusSeconds(599), jdbc.queryForObject(
                "SELECT last_activity_at FROM iam.client_sessions", OffsetDateTime.class).toInstant());
    }

    @Test void rejectsAtExactlyTenMinutesAndLater() {
        for (int seconds : new int[]{600, 601}) {
            jdbc.update("UPDATE iam.client_sessions SET last_activity_at = ?", now.minusSeconds(seconds).atOffset(ZoneOffset.UTC));
            assertFalse(active());
        }
    }

    @Test void rejectsRevokedSession() {
        jdbc.update("UPDATE iam.client_sessions SET revoked_at = ?", now.atOffset(ZoneOffset.UTC));
        assertFalse(active());
    }

    @Test void rejectsExpiredSessionEvenWithValidToken() {
        jdbc.update("UPDATE iam.client_sessions SET expires_at = ?", now.atOffset(ZoneOffset.UTC));
        assertFalse(active());
    }

    @Test void rejectsExpiredTokenEvenWithActiveSession() {
        assertFalse(validator.isActive(sid, client, now));
    }

    @Test void rejectsMissingSessionOrWrongOwner() {
        assertFalse(validator.isActive(null, client, now.plusSeconds(3600)));
        assertFalse(validator.isActive(UUID.randomUUID(), client, now.plusSeconds(3600)));
        assertFalse(validator.isActive(sid, UUID.randomUUID(), now.plusSeconds(3600)));
    }

    @Test void databaseFailureDoesNotBecomeAuthenticationSuccess() {
        jdbc.execute("DROP TABLE iam.client_sessions");
        assertThrows(org.springframework.dao.DataAccessException.class, this::active);
    }
}
