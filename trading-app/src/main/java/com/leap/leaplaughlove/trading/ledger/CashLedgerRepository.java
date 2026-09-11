package com.leap.leaplaughlove.trading.ledger;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Interface to represent the repository for cash ledger entries.
 * This repository provides methods to perform CRUD operations and custom queries on cash ledger entries.
 */
public interface CashLedgerRepository extends JpaRepository<CashLedgerEntry, UUID> {
    /**
     * Interface to represent the total amount for an account and currency combination.
     * Provides methods to access the account ID, currency, and total amount.
     */
    interface AccountTotal {
        UUID getAccountId();
        String getCurrency();
        BigDecimal getTotal();
    }
    /**
     * Sums the amounts for the specified account IDs, grouped by account and currency.
     * @param accountIds the list of account IDs to sum amounts for
     * @return a list of AccountTotal objects representing the total amounts for each account and currency combination
     */
    @Query("SELECT c.accountId AS accountId, c.currency AS currency, SUM(c.amount) AS total FROM CashLedgerEntry c " +
            "WHERE c.accountId IN :accountIds GROUP BY c.accountId, c.currency")
    List<AccountTotal> sumAmountsByAccountIds(@Param("accountIds") List<UUID> accountIds);

    /**
     * Sums the amounts for the specified account ID and currency.
     * @param accountId the account ID to sum amounts for
     * @param currency the currency to sum amounts for
     * @return the total amount for the specified account and currency
     */
    @Query("SELECT COALESCE(SUM(c.amount), 0) FROM CashLedgerEntry c WHERE c.accountId = :accountId AND c.currency = :currency")
    BigDecimal sumAmountByAccountIdAndCurrency(@Param("accountId") UUID accountId,
                                                @Param("currency") String currency);
}
