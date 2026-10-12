package com.leap.leaplaughlove.account.client;

import org.springframework.data.repository.Repository;

import java.util.Collection;
import java.util.List;
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

    /**
     * Finds the status of several clients at once.
     * @param clientIds the unique identifiers of the clients
     * @return the clients that exist, in no particular order
     */
    List<ClientStatus> findByClientIdIn(Collection<UUID> clientIds);
}
