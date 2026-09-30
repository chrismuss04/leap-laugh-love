// Session Timeout & Revocation: exercise signed tokens, the real filter, and persisted session updates.
package com.leap.leaplaughlove.iam.session;

import com.leap.leaplaughlove.common.security.JwtService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import java.time.*;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Sql(scripts = {"/db/client_registration_test_setup.sql", "/db/client_profile_test_setup.sql"})
class ClientSessionControllerTest {
    private static final UUID CLIENT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JwtService jwt;
    @Autowired private ClientSessionRepository sessions;

    private String token(UUID sid, Instant activity) {
        Instant start = Instant.now().minusSeconds(1200).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        Instant expiry = Instant.now().plusSeconds(2400).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        jdbc.update("INSERT INTO iam.client_sessions VALUES (?, ?, ?, ?, ?, NULL)",
                sid, CLIENT, start.atOffset(ZoneOffset.UTC), activity.atOffset(ZoneOffset.UTC),
                expiry.atOffset(ZoneOffset.UTC));
        return jwt.generateToken(CLIENT, "alice@example.com", sid, start, expiry);
    }

    private Instant activity(UUID sid) {
        return jdbc.queryForObject("SELECT last_activity_at FROM iam.client_sessions WHERE session_id = ?",
                OffsetDateTime.class, sid).toInstant();
    }

    @Test void activityUpdatesOnlyCurrentSessionAndPreservesAbsoluteExpiry() throws Exception {
        UUID sid = UUID.randomUUID(), other = UUID.randomUUID();
        Instant old = Instant.now().minusSeconds(300);
        String token = token(sid, old);
        token(other, old);
        Instant unchanged = activity(other);
        var expiry = jdbc.queryForObject("SELECT expires_at FROM iam.client_sessions WHERE session_id = ?",
                OffsetDateTime.class, sid);
        mvc.perform(post("/api/iam/session/activity").header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());
        assertTrue(activity(sid).isAfter(old));
        assertEquals(unchanged, activity(other));
        assertEquals(expiry, jdbc.queryForObject("SELECT expires_at FROM iam.client_sessions WHERE session_id = ?",
                OffsetDateTime.class, sid));
    }

    @Test void logoutRevokesOnlyCurrentSessionAndRejectsReuse() throws Exception {
        UUID sid = UUID.randomUUID();
        String token = token(sid, Instant.now());
        String other = token(UUID.randomUUID(), Instant.now());
        mvc.perform(post("/api/iam/session/logout").header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());
        assertNotNull(jdbc.queryForObject("SELECT revoked_at FROM iam.client_sessions WHERE session_id = ?",
                OffsetDateTime.class, sid));
        mvc.perform(get("/api/iam/v1/clients/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/iam/session/activity").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/iam/v1/clients/me").header("Authorization", "Bearer " + other))
                .andExpect(status().isOk());
    }

    @Test void idleSessionCannotBeRevived() throws Exception {
        UUID sid = UUID.randomUUID();
        String token = token(sid, Instant.now().minusSeconds(600));
        Instant old = activity(sid);
        mvc.perform(post("/api/iam/session/activity").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
        assertEquals(old, activity(sid));
    }

    @Test void endpointsRequireAuthentication() throws Exception {
        for (String route : new String[]{"activity", "logout"}) {
            mvc.perform(post("/api/iam/session/" + route)).andExpect(status().isUnauthorized());
            mvc.perform(post("/api/iam/session/" + route).header("Authorization", "Bearer invalid"))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Test void updateRechecksRevocationAfterFilterValidation() {
        UUID sid = UUID.randomUUID();
        token(sid, Instant.now());
        Instant old = activity(sid);
        sessions.revoke(sid, CLIENT, Instant.now());
        assertFalse(sessions.recordActivity(sid, CLIENT, Instant.now()));
        assertEquals(old, activity(sid));
    }

    @Test void updateRejectsExactIdleBoundaryAndAbsoluteExpiry() {
        UUID sid = UUID.randomUUID();
        Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        token(sid, now.minusSeconds(600));
        assertFalse(sessions.recordActivity(sid, CLIENT, now));
        jdbc.update("UPDATE iam.client_sessions SET last_activity_at = ?, expires_at = ? WHERE session_id = ?",
                now.atOffset(ZoneOffset.UTC), now.atOffset(ZoneOffset.UTC), sid);
        assertFalse(sessions.recordActivity(sid, CLIENT, now));
    }

    @Test void anotherClientCannotUpdateOrRevokeSession() {
        UUID sid = UUID.randomUUID();
        token(sid, Instant.now().minusSeconds(60));
        Instant old = activity(sid);
        UUID stranger = UUID.randomUUID();
        assertFalse(sessions.recordActivity(sid, stranger, Instant.now()));
        sessions.revoke(sid, stranger, Instant.now());
        assertEquals(old, activity(sid));
        assertNull(jdbc.queryForObject("SELECT revoked_at FROM iam.client_sessions WHERE session_id = ?",
                OffsetDateTime.class, sid));
    }
}
