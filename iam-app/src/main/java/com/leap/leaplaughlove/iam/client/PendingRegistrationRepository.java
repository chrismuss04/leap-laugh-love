package com.leap.leaplaughlove.iam.client;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

// Every write joins the caller's transaction, so an application is only stored or spent together
// with the change it belongs to.
@Repository
@Transactional(propagation = Propagation.MANDATORY)
public class PendingRegistrationRepository {

    private static final RowMapper<PendingRegistration> ROW_MAPPER = (rs, rowNum) -> new PendingRegistration(
            rs.getObject("registration_id", UUID.class), rs.getString("token_hash"),
            rs.getString("email"), rs.getString("phone"), rs.getString("full_name"),
            rs.getObject("date_of_birth", LocalDate.class), rs.getString("ssn"),
            rs.getString("address_line1"), rs.getString("address_line2"), rs.getString("city"),
            rs.getString("state_region"), rs.getString("postal_code"), rs.getString("country_code"),
            rs.getString("experience_level"), rs.getBigDecimal("initial_deposit_amount"),
            rs.getString("password_hash"),
            rs.getObject("created_at", OffsetDateTime.class).toInstant(),
            rs.getObject("expires_at", OffsetDateTime.class).toInstant());

    private final JdbcTemplate jdbcTemplate;

    public PendingRegistrationRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    // Stores an application, dropping any earlier one for the same email so only the newest link works.
    public void replace(PendingRegistration pending) {
        jdbcTemplate.update("DELETE FROM iam.pending_registrations WHERE email = ?", pending.email());
        jdbcTemplate.update("""
                INSERT INTO iam.pending_registrations
                    (registration_id, token_hash, email, phone, full_name, date_of_birth, ssn,
                     address_line1, address_line2, city, state_region, postal_code, country_code,
                     experience_level, initial_deposit_amount, password_hash, created_at, expires_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, pending.registrationId(), pending.tokenHash(), pending.email(), pending.phone(),
                pending.fullName(), pending.dateOfBirth(), pending.ssn(),
                pending.addressLine1(), pending.addressLine2(), pending.city(), pending.stateRegion(),
                pending.postalCode(), pending.countryCode(), pending.experienceLevel(),
                pending.initialDepositAmount(), pending.passwordHash(),
                pending.createdAt().atOffset(ZoneOffset.UTC), pending.expiresAt().atOffset(ZoneOffset.UTC));
    }

    // An unconfirmed application holds an SSN and a password hash, so it is not kept past its link.
    public void deleteExpired(Instant now) {
        jdbcTemplate.update("DELETE FROM iam.pending_registrations WHERE expires_at <= ?",
                now.atOffset(ZoneOffset.UTC));
    }

    // Removes and returns the application a link was issued for. The delete decides who gets it,
    // so a link submitted twice at the same moment opens one client, not two.
    public Optional<PendingRegistration> consume(String tokenHash, Instant now) {
        Optional<PendingRegistration> pending = jdbcTemplate.query("""
                SELECT * FROM iam.pending_registrations WHERE token_hash = ? AND expires_at > ?
                """, ROW_MAPPER, tokenHash, now.atOffset(ZoneOffset.UTC)).stream().findFirst();
        if (pending.isEmpty()) {
            return pending;
        }
        int spent = jdbcTemplate.update("DELETE FROM iam.pending_registrations WHERE registration_id = ?",
                pending.get().registrationId());
        return spent == 1 ? pending : Optional.empty();
    }
}
