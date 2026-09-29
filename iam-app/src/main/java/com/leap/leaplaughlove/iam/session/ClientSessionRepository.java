// Session Timeout & Revocation: persist each successful login without storing its JWT.
package com.leap.leaplaughlove.iam.session;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

@Repository
public class ClientSessionRepository {
    private final JdbcTemplate jdbcTemplate;

    public ClientSessionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    // Join the login transaction so session storage and credential updates commit together.
    @Transactional(propagation = Propagation.MANDATORY)
    public void create(UUID sessionId, UUID clientId, Instant issuedAt, Instant expiresAt) {
        jdbcTemplate.update("""
                INSERT INTO iam.client_sessions
                    (session_id, client_id, created_at, last_activity_at, expires_at)
                VALUES (?, ?, ?, ?, ?)
                """, sessionId, clientId, issuedAt.atOffset(ZoneOffset.UTC),
                issuedAt.atOffset(ZoneOffset.UTC), expiresAt.atOffset(ZoneOffset.UTC));
    }
}
