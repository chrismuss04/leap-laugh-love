// Password Recovery: persist each reset link without storing its token.
package com.leap.leaplaughlove.iam.auth;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

/**
 * Persists each password reset link without storing its token, only a hash of it.
 * Every write joins the caller's transaction, so a link is only issued or spent together with
 * the change it belongs to.
 */
@Repository
@Transactional(propagation = Propagation.MANDATORY)
public class PasswordResetTokenRepository {
    private final JdbcTemplate jdbcTemplate;

    /**
     * Constructs a new PasswordResetTokenRepository.
     * @param jdbcTemplate access to the iam schema
     */
    public PasswordResetTokenRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Stores a newly issued reset link.
     * @param tokenId the link's ID
     * @param clientId the client the link was issued to
     * @param tokenHash the hash of the link's token
     * @param createdAt when the link was issued
     * @param expiresAt when the link stops working
     */
    public void create(UUID tokenId, UUID clientId, String tokenHash, Instant createdAt, Instant expiresAt) {
        jdbcTemplate.update("""
                INSERT INTO iam.password_reset_tokens
                    (token_id, client_id, token_hash, created_at, expires_at)
                VALUES (?, ?, ?, ?, ?)
                """, tokenId, clientId, tokenHash, createdAt.atOffset(ZoneOffset.UTC),
                expiresAt.atOffset(ZoneOffset.UTC));
    }

    /**
     * Marks every unused link for a client as used. Only the newest link works, and none survive
     * a completed reset.
     * @param clientId the client whose links are invalidated
     * @param now the time to record as when they were used
     */
    public void invalidateAll(UUID clientId, Instant now) {
        jdbcTemplate.update("""
                UPDATE iam.password_reset_tokens SET used_at = ?
                WHERE client_id = ? AND used_at IS NULL
                """, now.atOffset(ZoneOffset.UTC), clientId);
    }

    /**
     * Whether a link can still be used, so the reset page can turn away a dead link before asking
     * for a password. Read-only, so unlike the writes it needs no caller transaction.
     * @param tokenHash the hash of the link's token
     * @param now the current time
     * @return true if the link exists, is unused and has not expired
     */
    @Transactional(readOnly = true)
    public boolean isUsable(String tokenHash, Instant now) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM iam.password_reset_tokens
                WHERE token_hash = ? AND used_at IS NULL AND expires_at > ?
                """, Integer.class, tokenHash, now.atOffset(ZoneOffset.UTC));
        return count != null && count == 1;
    }

    /**
     * Spends a link. The update is conditional, so a link can be spent once even if it is
     * submitted twice at the same moment.
     * @param tokenHash the hash of the link's token
     * @param now the current time
     * @return the client the link was issued to, or empty if it was already used or has expired
     */
    public Optional<UUID> consume(String tokenHash, Instant now) {
        int spent = jdbcTemplate.update("""
                UPDATE iam.password_reset_tokens SET used_at = ?
                WHERE token_hash = ? AND used_at IS NULL AND expires_at > ?
                """, now.atOffset(ZoneOffset.UTC), tokenHash, now.atOffset(ZoneOffset.UTC));
        if (spent != 1) {
            return Optional.empty();
        }
        return Optional.ofNullable(jdbcTemplate.queryForObject(
                "SELECT client_id FROM iam.password_reset_tokens WHERE token_hash = ?", UUID.class, tokenHash));
    }
}
