package com.leap.leaplaughlove.iam.client;

import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.util.UUID;

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

    protected ClientCredentials() {
    }

    public ClientCredentials(UUID clientId, String passwordHash, int failedAttempts, OffsetDateTime lastLoginAt) {
        this.clientId = clientId;
        this.passwordHash = passwordHash;
        this.failedAttempts = failedAttempts;
        this.lastLoginAt = lastLoginAt;
    }

    public UUID getClientId() { return clientId; }
    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }
    public int getFailedAttempts() { return failedAttempts; }
    public void incrementFailedAttempts() { this.failedAttempts++; }
    public void resetFailedAttempts() { this.failedAttempts = 0; }
    public OffsetDateTime getLastLoginAt() { return lastLoginAt; }
    public void setLastLoginAt(OffsetDateTime lastLoginAt) { this.lastLoginAt = lastLoginAt; }
}
