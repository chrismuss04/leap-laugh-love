package com.leap.leaplaughlove.account.ledger;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for cash ledger entries.
 * Provides methods to query and aggregate cash ledger data by account and
 * currency.
 */
public interface CashLedgerRepository extends JpaRepository<CashLedgerEntry, UUID> {

    /**
     * Interface representing the total cash amount for an account and currency.
     */
    interface AccountTotal {
        /**
         * Returns the accountId for which the total is calculated
         * 
         * @return accountId
         */
        UUID getAccountId();

        /**
         * Returns the currency for which the total is calculated
         * 
         * @return currency type
         */
        String getCurrency();

        /**
         * Returns the total amount for the specified account and currency
         * 
         * @return total amount for the Account
         */
        BigDecimal getTotal();
    }

    /**
     * Sums the cash amounts for the specified account IDs, grouped by account and
     * currency.
     * 
     * @param accountIds the list of account IDs to sum amounts for
     * @return a list of AccountTotal objects representing the summed amounts by
     *         account and currency
     */
    @Query("SELECT c.accountId AS accountId, c.currency AS currency, SUM(c.amount) AS total FROM CashLedgerEntry c " +
            "WHERE c.accountId IN :accountIds GROUP BY c.accountId, c.currency")
    List<AccountTotal> sumAmountsByAccountIds(@Param("accountIds") List<UUID> accountIds);

    /**
     * Sums the cash amounts for the specified account ID and currency.
     * 
     * @param accountId the account ID to sum amounts for
     * @param currency  the currency to sum amounts for
     * @return the total cash amount for the specified account and currency
     */
    @Query("SELECT COALESCE(SUM(c.amount), 0) FROM CashLedgerEntry c WHERE c.accountId = :accountId AND c.currency = :currency")
    BigDecimal sumAmountByAccountIdAndCurrency(@Param("accountId") UUID accountId,
            @Param("currency") String currency);

    /**
     * Finds every entry on the given accounts recorded after a point in time, used
     * to walk the
     * current cash balance back to what it was at that time.
     * 
     * @param accountIds the account IDs to include
     * @param after      the exclusive lower bound on the entry's creation time
     * @return the matching entries, in no particular order
     */
    List<CashLedgerEntry> findByAccountIdInAndCreatedAtAfter(Collection<UUID> accountIds, OffsetDateTime after);

    /**
     * Finds the most recent cash ledger entry for a given execution ID.
     * 
     * @param executionId the execution ID to search for
     * @return an Optional containing the most recent cash ledger entry for the
     *         given execution ID, or empty if no entry is found
     */
    Optional<CashLedgerEntry> findFirstByExecutionId(UUID executionId);
}
