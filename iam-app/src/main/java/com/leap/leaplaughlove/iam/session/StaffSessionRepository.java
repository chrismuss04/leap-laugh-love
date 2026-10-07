// Staff roles: persist each staff login the way ClientSessionRepository does for clients, without
// storing its JWT. Staff sessions get the same idle timeout and revocation.
package com.leap.leaplaughlove.iam.session;

import com.leap.leaplaughlove.common.security.ClientSessionValidator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * Repository for managing staff sessions, including creation, activity recording, and revocation.
 * 
 */
@Repository
public class StaffSessionRepository {
    private final JdbcTemplate jdbcTemplate;

    /**
     * Constructs a new StaffSessionRepository with the given JdbcTemplate.
     *
     * @param jdbcTemplate the JdbcTemplate to use for database operations
     */
    public StaffSessionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Creates a new staff session in the database.
     * Join the login transaction so session storage and credential updates commit together.
     * @param sessionId the ID of the new staff session
     * @param staffId the ID of the staff member
     * @param issuedAt the timestamp when the session was issued
     * @param expiresAt the timestamp when the session will expire
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void create(UUID sessionId, UUID staffId, Instant issuedAt, Instant expiresAt) {
        jdbcTemplate.update("""
                INSERT INTO iam.staff_sessions
                    (session_id, staff_id, created_at, last_activity_at, expires_at)
                VALUES (?, ?, ?, ?, ?)
                """, sessionId, staffId, issuedAt.atOffset(ZoneOffset.UTC),
                issuedAt.atOffset(ZoneOffset.UTC), expiresAt.atOffset(ZoneOffset.UTC));
    }

    /**
     * Records activity for a staff session, updating the last activity timestamp if the session is still valid.
     *
     * @param sessionId the ID of the staff session
     * @param staffId the ID of the staff member
     * @param now the current timestamp
     * @return true if the activity was recorded, false otherwise
     */ 
    @Transactional
    public boolean recordActivity(UUID sessionId, UUID staffId, Instant now) {
        return jdbcTemplate.update("""
                UPDATE iam.staff_sessions
                SET last_activity_at = GREATEST(last_activity_at, ?)
                WHERE session_id = ? AND staff_id = ? AND revoked_at IS NULL
                  AND expires_at > ? AND last_activity_at > ?
                """, now.atOffset(ZoneOffset.UTC), sessionId, staffId, now.atOffset(ZoneOffset.UTC),
                now.minus(ClientSessionValidator.IDLE_TIMEOUT).atOffset(ZoneOffset.UTC)) == 1;
    }

    /**
     * Revokes only this login session, preserving the staff member's other active sessions.
     * 
     * @param sessionId the ID of the staff session
     * @param staffId the ID of the staff member
     * @param now the current timestamp
     */
    @Transactional
    public void revoke(UUID sessionId, UUID staffId, Instant now) {
        jdbcTemplate.update("""
                UPDATE iam.staff_sessions SET revoked_at = ?
                WHERE session_id = ? AND staff_id = ? AND revoked_at IS NULL
                """, now.atOffset(ZoneOffset.UTC), sessionId, staffId);
    }
}
