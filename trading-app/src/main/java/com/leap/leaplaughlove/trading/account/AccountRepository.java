package com.leap.leaplaughlove.trading.account;

// LLL-133
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Interface for managing accounts in the trading system.
 * Provides a method to query accounts by client ID and status.
 */
public interface AccountRepository extends JpaRepository<Account, UUID> {
    /**
     * Finds all accounts for a given client ID and status.
     * @param clientId the unique identifier of the client
     * @param status the status of the accounts to retrieve
     * @return a list of accounts matching the specified client ID and status
     */
    List<Account> findByClientIdAndStatus(UUID clientId, String status);

    Optional<Account> findByAccountIdAndClientId(UUID accountId, UUID clientId);

    // LLL-133
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
