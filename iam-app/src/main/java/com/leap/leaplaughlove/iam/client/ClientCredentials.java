package com.leap.leaplaughlove.iam.client;

import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Represents the client credentials for authentication purposes.
 * Contains information such as the client ID, password hash, failed login attempts, and the timestamp of the last login.
 */
@Entity
@Table(name = "client_credentials", schema = "iam")
public class ClientCredentials {

    @Id
    @Column(name = "client_id")
    private UUID clientId;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(name = "failed_attempts", nullable = false)
    private int failedAttempts;

    @Column(name = "last_login_at")
    private OffsetDateTime lastLoginAt;

    /**
     * Protected no-argument constructor for JPA.
     */
    protected ClientCredentials() {
    }
    /**
     * Method to create a new ClientCredentials entity with the specified details.
     * @param clientId the unique identifier of the client
     * @param passwordHash the hashed password of the client
     * @param failedAttempts the number of failed login attempts
     * @param lastLoginAt the timestamp of the last login
     */
    public ClientCredentials(UUID clientId, String passwordHash, int failedAttempts, OffsetDateTime lastLoginAt) {
        this.clientId = clientId;
        this.passwordHash = passwordHash;
        this.failedAttempts = failedAttempts;
        this.lastLoginAt = lastLoginAt;
    }

    /**
     * Method to retrieve the unique identifier of the client.
     * @return the client ID
     */
    public UUID getClientId() { return clientId; }
    /**
     * Method to retrieve the password hash of the client.
     * @return the password hash of the client
     */
    public String getPasswordHash() { return passwordHash; }
    /**
     * Method to update the password hash of the client.
     * @param passwordHash the new password hash of the client
     */
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }
    /**
     * Method to retrieve the number of failed login attempts.
     * @return the number of failed login attempts
     */
    public int getFailedAttempts() { return failedAttempts; }
    /**
     * Method to increment the number of failed login attempts by one.
     */
    public void incrementFailedAttempts() { this.failedAttempts++; }
    /**
     * Method to reset the number of failed login attempts to zero.
     */
    public void resetFailedAttempts() { this.failedAttempts = 0; }
    /**
     * Method to retrieve the timestamp of the last login.
     * @return the timestamp of the last login
     */
    public OffsetDateTime getLastLoginAt() { return lastLoginAt; }
    /**
     * Method to update the timestamp of the last login.
     * @param lastLoginAt the new timestamp of the last login
     */
    public void setLastLoginAt(OffsetDateTime lastLoginAt) { this.lastLoginAt = lastLoginAt; }
}
