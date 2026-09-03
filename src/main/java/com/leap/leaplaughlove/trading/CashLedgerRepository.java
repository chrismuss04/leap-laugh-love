package com.leap.leaplaughlove.trading;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public interface CashLedgerRepository extends JpaRepository<CashLedgerEntry, UUID> {

    /** Projection for the grouped-by-account/currency sum below; missing (accountId, currency) pairs have no entries. */
    interface AccountTotal {
        UUID getAccountId();
        String getCurrency();
        BigDecimal getTotal();
    }
    /**
     * Sums the amounts of cash ledger entries grouped by account ID and currency for the given list of account IDs.
     * @param accountIds
     * @return a list of AccountTotal containing the summed amounts for each account ID and currency combination.
     */
    @Query("SELECT c.accountId AS accountId, c.currency AS currency, SUM(c.amount) AS total FROM CashLedgerEntry c " +
            "WHERE c.accountId IN :accountIds GROUP BY c.accountId, c.currency")
    List<AccountTotal> sumAmountsByAccountIds(@Param("accountIds") List<UUID> accountIds);

    /**
     * Sums the amounts of cash ledger entries for the given account ID and currency.
     * @param accountId
     * @param currency
     * @return the summed amount for the specified account ID and currency combination.
     */
    // since query groups by currency, add COALESCE which also considers the currency
    @Query("SELECT COALESCE(SUM(c.amount), 0) FROM CashLedgerEntry c WHERE c.accountId = :accountId AND c.currency = :currency")
    BigDecimal sumAmountByAccountIdAndCurrency(@Param("accountId") UUID accountId,
                                                @Param("currency") String currency);
}
