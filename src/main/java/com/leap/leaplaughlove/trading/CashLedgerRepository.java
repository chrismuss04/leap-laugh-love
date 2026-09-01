package com.leap.leaplaughlove.trading;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.UUID;

public interface CashLedgerRepository extends JpaRepository<CashLedgerEntry, UUID> {

    @Query("SELECT COALESCE(SUM(c.amount), 0) FROM CashLedgerEntry c WHERE c.accountId = :accountId")
    BigDecimal sumAmountByAccountId(@Param("accountId") UUID accountId);
}
