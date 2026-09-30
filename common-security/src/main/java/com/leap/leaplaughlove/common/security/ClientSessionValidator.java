// Session Timeout & Revocation: read-only validation shared by every protected service.
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
        Instant now = clock.instant();
        if (sessionId == null || clientId == null || tokenExpiry == null || !tokenExpiry.isAfter(now)) {
            return false;
        }
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM iam.client_sessions
                WHERE session_id = ? AND client_id = ? AND revoked_at IS NULL
                  AND expires_at > ? AND last_activity_at > ?
                  AND last_activity_at <= ?
                """, Integer.class, sessionId, clientId, now.atOffset(ZoneOffset.UTC),
                now.minus(IDLE_TIMEOUT).atOffset(ZoneOffset.UTC), now.atOffset(ZoneOffset.UTC));
        return count != null && count == 1;
    }
}
