// Analyst login: staff sign in with iam.reporting_service_credentials, which are kept apart from
// client identities. Same lockout rules as client credentials.
package com.leap.leaplaughlove.iam.staff;

import com.leap.leaplaughlove.common.security.Role;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for staff sign-in credentials: looking a staff member up by email and recording the
 * outcome of each sign-in attempt.
 */
@Repository
public class StaffCredentialsRepository {

    /**
     * A staff member's sign-in credentials.
     * @param serviceId the staff member's ID, used as the subject of their session token
     * @param email the staff member's sign-in email
     * @param passwordHash the BCrypt hash of their password
     * @param role the staff role their sessions are granted
     * @param status ACTIVE, PENDING, LOCKED or DELETED
     * @param failedAttempts consecutive failed sign-ins since the last successful one
     */
    public record StaffCredentials(UUID serviceId, String email, String passwordHash, Role role,
                                   String status, int failedAttempts) {}

    private final JdbcTemplate jdbcTemplate;

    /**
     * Constructs a new StaffCredentialsRepository with the given JdbcTemplate.
     * @param jdbcTemplate the JdbcTemplate to use for database operations
     */
    public StaffCredentialsRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Finds a staff member's credentials by email.
     * @param email the sign-in email
     * @return the credentials, or empty if no staff member has this email
     */
    public Optional<StaffCredentials> findByEmail(String email) {
        return jdbcTemplate.query("""
                SELECT service_id, email, password_hash, role, status, failed_attempts
                FROM iam.reporting_service_credentials WHERE email = ?
                """, (rs, row) -> new StaffCredentials(
                        rs.getObject("service_id", UUID.class), rs.getString("email"),
                        rs.getString("password_hash"), Role.valueOf(rs.getString("role")),
                        rs.getString("status"), rs.getInt("failed_attempts")), email)
                .stream().findFirst();
    }

    /**
     * Whether any staff member signs in with this email.
     * @param email the email to check
     * @return true if the email belongs to a staff member
     */
    public boolean existsByEmail(String email) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM iam.reporting_service_credentials WHERE email = ?", Integer.class, email);
        return count != null && count > 0;
    }

    /**
     * Records a failed sign-in, locking the staff member out once they reach the limit.
     * @param serviceId the staff member's ID
     * @param maxFailedAttempts the number of failed attempts that locks the account
     * @return the failed attempt count after this one
     */
    public int recordFailedAttempt(UUID serviceId, int maxFailedAttempts) {
        jdbcTemplate.update("""
                UPDATE iam.reporting_service_credentials
                SET failed_attempts = failed_attempts + 1,
                    status = CASE WHEN failed_attempts + 1 >= ? THEN 'LOCKED' ELSE status END
                WHERE service_id = ?
                """, maxFailedAttempts, serviceId);
        Integer attempts = jdbcTemplate.queryForObject(
                "SELECT failed_attempts FROM iam.reporting_service_credentials WHERE service_id = ?",
                Integer.class, serviceId);
        return attempts == null ? 0 : attempts;
    }

    /**
     * Records a successful sign-in: clears failed attempts and sets the last login time.
     * @param serviceId the staff member's ID
     * @param now the time of the sign-in
     */
    public void recordSuccessfulLogin(UUID serviceId, Instant now) {
        jdbcTemplate.update("""
                UPDATE iam.reporting_service_credentials
                SET failed_attempts = 0, last_login_at = ?
                WHERE service_id = ?
                """, now.atOffset(ZoneOffset.UTC), serviceId);
    }
}
