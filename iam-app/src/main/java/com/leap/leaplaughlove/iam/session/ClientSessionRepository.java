// Session Timeout & Revocation: persist each successful login without storing its JWT.
package com.leap.leaplaughlove.iam.session;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * Persists each successful client login as a session, without storing its JWT.
 * Staff sessions are kept separately by {@link StaffSessionRepository}.
 */
@Repository
public class ClientSessionRepository {
    private final JdbcTemplate jdbcTemplate;

    /**
     * Constructs a new ClientSessionRepository.
     * @param jdbcTemplate access to the iam schema
     */
    public ClientSessionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Stores a new session. Joins the login transaction so session storage and credential
     * updates commit together.
     * @param sessionId the session's ID, also carried in the token
     * @param clientId the client who signed in
     * @param issuedAt when the session started
     * @param expiresAt when the session ends
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void create(UUID sessionId, UUID clientId, Instant issuedAt, Instant expiresAt) {
        jdbcTemplate.update("""
                INSERT INTO iam.client_sessions
                    (session_id, client_id, created_at, last_activity_at, expires_at)
                VALUES (?, ?, ?, ?, ?)
                """, sessionId, clientId, issuedAt.atOffset(ZoneOffset.UTC),
                issuedAt.atOffset(ZoneOffset.UTC), expiresAt.atOffset(ZoneOffset.UTC));
    }

    /**
     * Records activity on a session. The update is conditional, so expired, revoked or idle
     * sessions are never revived.
     * @param sessionId the session
     * @param clientId the session's client
     * @param now the time of the activity
     * @return true if the session was live and its activity was recorded
     */
    @Transactional
    public boolean recordActivity(UUID sessionId, UUID clientId, Instant now) {
        return jdbcTemplate.update("""
                UPDATE iam.client_sessions
                SET last_activity_at = GREATEST(last_activity_at, ?)
                WHERE session_id = ? AND client_id = ? AND revoked_at IS NULL
                  AND expires_at > ? AND last_activity_at > ?
                """, now.atOffset(ZoneOffset.UTC), sessionId, clientId, now.atOffset(ZoneOffset.UTC),
                now.minus(com.leap.leaplaughlove.common.security.ClientSessionValidator.IDLE_TIMEOUT)
                        .atOffset(ZoneOffset.UTC)) == 1;
    }

    /**
     * Revokes one login, preserving the client's sessions on other devices.
     * @param sessionId the session to revoke
     * @param clientId the session's client
     * @param now the time to record as the revocation
     */
    @Transactional
    public void revoke(UUID sessionId, UUID clientId, Instant now) {
        jdbcTemplate.update("""
                UPDATE iam.client_sessions SET revoked_at = ?
                WHERE session_id = ? AND client_id = ? AND revoked_at IS NULL
                """, now.atOffset(ZoneOffset.UTC), sessionId, clientId);
    }

    /**
     * Revokes every live session for a client. A password reset signs the client out everywhere,
     * since whoever knew the old password may still be signed in.
     * @param clientId the client whose sessions are revoked
     * @param now the time to record as the revocation
     */
    @Transactional
    public void revokeAll(UUID clientId, Instant now) {
        jdbcTemplate.update("""
                UPDATE iam.client_sessions SET revoked_at = ?
                WHERE client_id = ? AND revoked_at IS NULL
                """, now.atOffset(ZoneOffset.UTC), clientId);
    }
}
