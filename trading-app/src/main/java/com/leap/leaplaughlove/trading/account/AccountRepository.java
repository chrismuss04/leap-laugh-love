package com.leap.leaplaughlove.trading.account;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
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
}
