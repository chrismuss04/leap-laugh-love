package com.leap.leaplaughlove.trading.position;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PositionRepository extends JpaRepository<Position, PositionId> {

    /**
     * Locks the position row for the duration of the transaction so concurrent
     * executions against the same account/instrument can't lose an update.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM Position p WHERE p.accountId = :accountId AND p.instrumentId = :instrumentId")
    Optional<Position> findByIdForUpdate(@Param("accountId") UUID accountId, @Param("instrumentId") UUID instrumentId);

    interface PositionRow {
        String getInstrumentId();

        String getSymbol();

        String getInstrumentName();

        String getAssetClass();

        long getQuantity();

        BigDecimal getAvgCost();
    }

    @Query(value = """
            SELECT CAST(p.instrument_id AS VARCHAR) AS instrumentId,
                   i.symbol AS symbol,
                   i.instrument_name AS instrumentName,
                   i.asset_class AS assetClass,
                   p.quantity AS quantity,
                   p.avg_cost AS avgCost
            FROM trading.positions p
            JOIN trading.instruments i ON i.instrument_id = p.instrument_id
            WHERE p.account_id = :accountId AND p.quantity > 0
            ORDER BY i.symbol ASC
            """, nativeQuery = true)
    List<PositionRow> findPositionsByAccountId(@Param("accountId") UUID accountId);
}
