package com.leap.leaplaughlove.account.account;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

    /**
     * Locks an account owned by the specified client until the calling transaction ends.
     * Call within a write transaction before reading cash or holdings for validation.
     * Competing trades and withdrawals must acquire this same lock before validation.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM Account a WHERE a.accountId = :accountId AND a.clientId = :clientId")
    Optional<Account> findByAccountIdAndClientIdForUpdate(
            @Param("accountId") UUID accountId, @Param("clientId") UUID clientId);
}

