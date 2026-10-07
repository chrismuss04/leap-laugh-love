// Session Timeout & Revocation: read-only validation shared by every protected service, for
// client and staff sessions alike (see Role).
package com.leap.leaplaughlove.common.security;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

@Service
public class ClientSessionValidator {
    public static final Duration IDLE_TIMEOUT = Duration.ofMinutes(10);
    private final JdbcTemplate jdbc;
    private final Clock clock;

    @Autowired
    public ClientSessionValidator(JdbcTemplate jdbc) {
        this(jdbc, Clock.systemUTC());
    }

    ClientSessionValidator(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    // Never extend activity here: polling and market-data requests must not keep a login alive.
    public boolean isActive(UUID sessionId, UUID clientId, Instant tokenExpiry) {
        return isActive(Role.CLIENT, sessionId, clientId, tokenExpiry);
    }

    /**
     * Whether a session token's login is still live, looked up in its role's own session table:
     * iam.client_sessions for clients, iam.staff_sessions for staff. A token only ever matches a
     * session in its own table, so a client token can't pass as staff, or the other way round.
     * @param role the token's role
     * @param sessionId the token's session
     * @param subjectId the token's subject: a client ID, or a staff member's service_id
     * @param tokenExpiry the token's expiry
     * @return true if the session exists, is unrevoked, unexpired and not idle
     */
    public boolean isActive(Role role, UUID sessionId, UUID subjectId, Instant tokenExpiry) {
        Instant now = clock.instant();
        if (role == null || sessionId == null || subjectId == null || tokenExpiry == null
                || !tokenExpiry.isAfter(now)) {
            return false;
        }
        // Table and column names are fixed per role, never taken from the token.
        String sql = role.isStaff() ? """
                SELECT COUNT(*) FROM iam.staff_sessions
                WHERE session_id = ? AND staff_id = ? AND revoked_at IS NULL
                  AND expires_at > ? AND last_activity_at > ?
                  AND last_activity_at <= ?
                """ : """
                SELECT COUNT(*) FROM iam.client_sessions
                WHERE session_id = ? AND client_id = ? AND revoked_at IS NULL
                  AND expires_at > ? AND last_activity_at > ?
                  AND last_activity_at <= ?
                """;
        Integer count = jdbc.queryForObject(sql, Integer.class, sessionId, subjectId, now.atOffset(ZoneOffset.UTC),
                now.minus(IDLE_TIMEOUT).atOffset(ZoneOffset.UTC), now.atOffset(ZoneOffset.UTC));
        return count != null && count == 1;
    }
}
