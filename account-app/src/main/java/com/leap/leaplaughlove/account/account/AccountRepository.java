package com.leap.leaplaughlove.account.account;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
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
     * Finds the highest account number in the client's 1, 2, ... sequence, across every status, so a
     * closed or blocked account's number is never handed out again.
     * @param clientId the unique identifier of the client
     * @return the highest sequence number, or 0 if the client has no numbered accounts
     */
    @Query("SELECT COALESCE(MAX(a.accountSeq), 0) FROM Account a WHERE a.clientId = :clientId")
    int findMaxAccountSeq(@Param("clientId") UUID clientId);

    /**
     * Locks an account owned by the specified client until the calling transaction ends.
     * Call within a write transaction before reading cash or holdings for validation.
     * Competing trades and withdrawals must acquire this same lock before validation.
     * @param accountId the account to lock
     * @param clientId the client who must own it
     * @return the locked account, or empty if the client doesn't own one with that ID
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM Account a WHERE a.accountId = :accountId AND a.clientId = :clientId")
    Optional<Account> findByAccountIdAndClientIdForUpdate(
            @Param("accountId") UUID accountId, @Param("clientId") UUID clientId);

    /**
     * Finds the accounts the inactivity job has flagged.
     * @return the accounts the inactivity job has flagged
     */
    List<Account> findByInactiveSinceIsNotNull();

    /**
     * Finds the flagged accounts whose client has not yet been emailed.
     * @return the flagged accounts whose client has not yet been emailed about it
     */
    List<Account> findByInactiveSinceIsNotNullAndInactiveNotifiedAtIsNull();

    /**
     * Records that the client was emailed about the account's inactive period. Sets only this
     * column, so it cannot overwrite a change made to the account since it was read, and does
     * nothing if the account was reactivated in the meantime.
     * @param accountId the account
     * @param notifiedAt when the email was sent
     * @return the number of accounts updated, 0 or 1
     */
    @Modifying
    @Query("UPDATE Account a SET a.inactiveNotifiedAt = :notifiedAt "
            + "WHERE a.accountId = :accountId AND a.inactiveSince IS NOT NULL")
    int markInactiveNotified(@Param("accountId") UUID accountId, @Param("notifiedAt") OffsetDateTime notifiedAt);

    /** An account with nothing in it, and when it became empty. */
    interface EmptyAccount {
        /**
         * Returns the empty account.
         * @return the account's ID
         */
        UUID getAccountId();

        /**
         * Returns when the account became empty.
         * @return the time of its last ledger entry, or when it was opened if it has none
         */
        OffsetDateTime getEmptySince();
    }

    /**
     * Finds active accounts with no cash in any currency and no shares that have been empty since
     * before the cutoff. Cash only changes with a ledger entry, so an empty account has been empty
     * since its last entry, or since it was opened if it has none.
     * @param cutoff only accounts that became empty before this are returned
     * @return the empty accounts, with when each became empty
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

