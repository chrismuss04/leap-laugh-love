package com.leap.leaplaughlove.iam;

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

    @Column(name = "failed_sign_in_attempts", nullable = false)
    private int failedSignInAttempts;

    @Column(name = "locked_until")
    private OffsetDateTime lockedUntil;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    protected ClientCredentials() {
    }

    public UUID getClientId() {
        return clientId;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public int getFailedSignInAttempts() {
        return failedSignInAttempts;
    }

    public void setFailedSignInAttempts(int failedSignInAttempts) {
        this.failedSignInAttempts = failedSignInAttempts;
    }

    public OffsetDateTime getLockedUntil() {
        return lockedUntil;
    }

    public void setLockedUntil(OffsetDateTime lockedUntil) {
        this.lockedUntil = lockedUntil;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
