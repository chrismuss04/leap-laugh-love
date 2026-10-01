package com.leap.leaplaughlove.account.client;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

import java.util.UUID;

/**
 * Read-only view of a client's status in the iam schema. account-app never writes to it; it only
 * needs the status to decide how a newly opened account starts out.
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
        this.clientId = clientId;
        this.status = status;
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
}
