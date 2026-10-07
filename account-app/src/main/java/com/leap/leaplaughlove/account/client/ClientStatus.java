package com.leap.leaplaughlove.account.client;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

import java.util.UUID;

/**
 * Read-only view of a client's status in the iam schema. account-app never writes to it; it only
 * needs the status to decide how a newly opened account starts out, and the email address to
 * tell the client their account has gone inactive.
 */
@Entity
@Immutable
@Table(name = "clients", schema = "iam")
public class ClientStatus {

    @Id
    @Column(name = "client_id")
    private UUID clientId;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "email", nullable = false)
    private String email;

    /**
     * Protected no-argument constructor for JPA.
     */
    protected ClientStatus() {
    }

    /**
     * Constructs a ClientStatus.
     * @param clientId the unique identifier of the client
     * @param status the client's status (PENDING, ACTIVE, LOCKED or DELETED)
     */
    public ClientStatus(UUID clientId, String status) {
        this(clientId, status, null);
    }

    /**
     * Constructs a ClientStatus with the client's email address.
     * @param clientId the unique identifier of the client
     * @param status the client's status (PENDING, ACTIVE, LOCKED or DELETED)
     * @param email the client's email address
     */
    public ClientStatus(UUID clientId, String status, String email) {
        this.clientId = clientId;
        this.status = status;
        this.email = email;
    }

    /**
     * Returns the client identifier.
     * @return the unique identifier of the client
     */
    public UUID getClientId() { return clientId; }

    /**
     * Returns the client's status.
     * @return the status of the client
     */
    public String getStatus() { return status; }

    /**
     * Returns the client's email address.
     * @return the email address of the client
     */
    public String getEmail() { return email; }
}
