// Session Timeout & Revocation: read-only validation shared by every protected service, for
// client and staff sessions alike (see Role).
package com.leap.leaplaughlove.common.security;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.sql.PreparedStatement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Checks that the login session behind a token is still live. Read-only and shared by every
 * protected service, for client and staff sessions alike.
 */
@Service
public class ClientSessionValidator {
    /** How long a session may go without activity before it stops being accepted. */
    public static final Duration IDLE_TIMEOUT = Duration.ofMinutes(10);
    /**
     * How far ahead of this service's clock a session's last activity may be. iam-app records
     * activity at its own "now", which can land between another request reading the clock and
     * querying the session - a few milliseconds in that request's future. Without this slack that
     * request was refused with a 401, and the frontend signed the client out mid-session.
     */
    static final Duration CLOCK_SKEW = Duration.ofSeconds(30);
    private final JdbcTemplate jdbc;
    private final Clock clock;

    /**
     * Creates a validator that reads sessions through {@code jdbc}, timed by the UTC system clock.
     * @param jdbc access to the shared session tables
     */
    @Autowired
    public ClientSessionValidator(JdbcTemplate jdbc) {
        this(jdbc, Clock.systemUTC());
    }

    ClientSessionValidator(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /**
     * Whether a client's session is still live. Never extends the session's activity: polling and
     * market-data requests must not keep a login alive.
     * @param sessionId the token's session
     * @param clientId the token's client
     * @param tokenExpiry the token's expiry
     * @return true if the session exists, is unrevoked, unexpired and not idle
     */
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
        if (role == null || sessionId == null || subjectId == null) {
            return false;
        }
        if (tokenExpiry == null || !tokenExpiry.isAfter(now)) {
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
                now.minus(IDLE_TIMEOUT).atOffset(ZoneOffset.UTC), now.plus(CLOCK_SKEW).atOffset(ZoneOffset.UTC));
        return count != null && count == 1;
    }

    /**
     * Which of the given client sessions are still live, in one query however many there are. Like
     * {@link #isActive}, never extends a session's activity. Token expiry isn't stored here, so the
     * caller checks that itself.
     * @param sessionIds the client sessions to look up
     * @return the client each live session belongs to, keyed by session; ended or unknown
     *     sessions are left out
     */
    public Map<UUID, UUID> activeClientSessions(Collection<UUID> sessionIds) {
        if (sessionIds.isEmpty()) {
            return Map.of();
        }
        Instant now = clock.instant();
        String sql = """
                SELECT session_id, client_id FROM iam.client_sessions
                WHERE session_id = ANY (?) AND revoked_at IS NULL
                  AND expires_at > ? AND last_activity_at > ?
                  AND last_activity_at <= ?
                """;
        Map<UUID, UUID> active = new HashMap<>();
        jdbc.query(connection -> {
            PreparedStatement statement = connection.prepareStatement(sql);
            statement.setArray(1, connection.createArrayOf("uuid", sessionIds.toArray()));
            statement.setObject(2, now.atOffset(ZoneOffset.UTC));
            statement.setObject(3, now.minus(IDLE_TIMEOUT).atOffset(ZoneOffset.UTC));
            statement.setObject(4, now.plus(CLOCK_SKEW).atOffset(ZoneOffset.UTC));
            return statement;
        }, row -> {
            active.put(row.getObject("session_id", UUID.class), row.getObject("client_id", UUID.class));
        });
        return active;
    }
}
