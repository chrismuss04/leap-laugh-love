package com.leap.leaplaughlove.account.account;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Interface for managing accounts in the system.
 * Provides methods to retrieve accounts based on client ID, status, and account ID.
 */
public interface AccountRepository extends JpaRepository<Account, UUID> {
    /**
     * Retrieves a list of accounts for the specified client with the given status.
     * @param clientId the unique identifier of the client
     * @param status the status of the accounts to retrieve
     * @return a list of accounts matching the specified client ID and status
     */
    List<Account> findByClientIdAndStatus(UUID clientId, String status);

    /**
     * Retrieves a list of accounts for the specified client.
     * @param clientId the unique identifier of the client
     * @return a list of accounts matching the specified client ID
     */
    List<Account> findByClientId(UUID clientId);

    /**
     * Retrieves an account by its unique identifier and the client ID it belongs to.
     * @param accountId the unique identifier of the account
     * @param clientId the unique identifier of the client
     * @return an Optional containing the account if found, or empty if not found
     */
    Optional<Account> findByAccountIdAndClientId(UUID accountId, UUID clientId);
}

