package com.leap.leaplaughlove.iam.client;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.UUID;

/**
 * Interface for managing client credentials in the IAM system.
 */
public interface ClientCredentialsRepository extends JpaRepository<ClientCredentials, UUID> {
    /**
     * Retrieves the client credentials for the specified client ID.
     * @param clientId the unique identifier of the client
     * @return an Optional containing the client credentials if found, or empty if not found
     */
    Optional<ClientCredentials> findByClientId(UUID clientId);
}
