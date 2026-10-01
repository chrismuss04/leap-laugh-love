package com.leap.leaplaughlove.account.account;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
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
     * Checks whether an account with the given account number already exists.
     * @param accountNumber the account number to look for
     * @return true if an account already uses the number
     */
    boolean existsByAccountNumber(String accountNumber);

    /**
     * Locks an account owned by the specified client until the calling transaction ends.
     * Call within a write transaction before reading cash or holdings for validation.
     * Competing trades and withdrawals must acquire this same lock before validation.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM Account a WHERE a.accountId = :accountId AND a.clientId = :clientId")
    Optional<Account> findByAccountIdAndClientIdForUpdate(
            @Param("accountId") UUID accountId, @Param("clientId") UUID clientId);

    /** @return the accounts the inactivity job has flagged */
    List<Account> findByInactiveSinceIsNotNull();

    /** An account with nothing in it, and when it became empty. */
    interface EmptyAccount {
        UUID getAccountId();
        OffsetDateTime getEmptySince();
    }

    /**
     * Finds active accounts with no cash in any currency and no shares that have been empty since
     * before the cutoff. Cash only changes with a ledger entry, so an empty account has been empty
     * since its last entry, or since it was opened if it has none.
     */
    @Query("""
            SELECT a.accountId AS accountId, COALESCE(MAX(c.createdAt), a.createdAt) AS emptySince
            FROM Account a LEFT JOIN CashLedgerEntry c ON c.accountId = a.accountId
            WHERE a.status = 'ACTIVE'
              AND NOT EXISTS (SELECT 1 FROM Position p WHERE p.accountId = a.accountId AND p.quantity <> 0)
              AND NOT EXISTS (SELECT 1 FROM CashLedgerEntry b WHERE b.accountId = a.accountId
                              GROUP BY b.currency HAVING SUM(b.amount) <> 0)
            GROUP BY a.accountId, a.createdAt
            HAVING COALESCE(MAX(c.createdAt), a.createdAt) < :cutoff""")
    List<EmptyAccount> findEmptySince(@Param("cutoff") OffsetDateTime cutoff);
}

