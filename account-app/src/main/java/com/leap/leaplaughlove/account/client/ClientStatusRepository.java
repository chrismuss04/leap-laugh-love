package com.leap.leaplaughlove.account.client;

import org.springframework.data.repository.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * Read-only lookup of a client's status.
 */
public interface ClientStatusRepository extends Repository<ClientStatus, UUID> {

    /**
     * Finds a client's status by client ID.
     * @param clientId the unique identifier of the client
     * @return an Optional containing the client's status, or empty if the client does not exist
     */
    Optional<ClientStatus> findById(UUID clientId);
}
