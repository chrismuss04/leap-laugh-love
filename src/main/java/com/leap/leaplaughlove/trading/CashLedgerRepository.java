package com.leap.leaplaughlove.trading;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public interface CashLedgerRepository extends JpaRepository<CashLedgerEntry, UUID> {

    /** Projection for the grouped-by-account sum below; accounts with no entries are simply absent. */
    interface AccountTotal {
        UUID getAccountId();
        BigDecimal getTotal();
    }

    @Query("SELECT c.accountId AS accountId, SUM(c.amount) AS total FROM CashLedgerEntry c " +
            "WHERE c.accountId IN :accountIds GROUP BY c.accountId")
    List<AccountTotal> sumAmountsByAccountIds(@Param("accountIds") List<UUID> accountIds);
}
