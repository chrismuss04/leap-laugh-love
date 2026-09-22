package com.leap.leaplaughlove.account.ledger;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Repository for cash ledger entries.
 * Provides methods to query and aggregate cash ledger data by account and currency.
 */
public interface CashLedgerRepository extends JpaRepository<CashLedgerEntry, UUID> {

    /**
     * Interface representing the total cash amount for an account and currency.
     */
    interface AccountTotal {
        UUID getAccountId();
        String getCurrency();
        BigDecimal getTotal();
    }

    /**
     * Sums the cash amounts for the specified account IDs, grouped by account and currency.
     * @param accountIds the list of account IDs to sum amounts for
     * @return a list of AccountTotal objects representing the summed amounts by account and currency
     */
    @Query("SELECT c.accountId AS accountId, c.currency AS currency, SUM(c.amount) AS total FROM CashLedgerEntry c " +
            "WHERE c.accountId IN :accountIds GROUP BY c.accountId, c.currency")
    List<AccountTotal> sumAmountsByAccountIds(@Param("accountIds") List<UUID> accountIds);

    /**
    * Sums the cash amounts for the specified account ID and currency.
    * @param accountId the account ID to sum amounts for
    * @param currency the currency to sum amounts for
    * @return the total cash amount for the specified account and currency
    */
    @Query("SELECT COALESCE(SUM(c.amount), 0) FROM CashLedgerEntry c WHERE c.accountId = :accountId AND c.currency = :currency")
    BigDecimal sumAmountByAccountIdAndCurrency(@Param("accountId") UUID accountId,
                                                @Param("currency") String currency);
}

