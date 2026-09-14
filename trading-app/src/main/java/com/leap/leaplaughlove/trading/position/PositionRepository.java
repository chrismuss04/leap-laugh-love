package com.leap.leaplaughlove.trading.position;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public interface PositionRepository extends JpaRepository<Position, PositionId> {

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
            WHERE p.account_id = :accountId
            ORDER BY i.symbol ASC
            """, nativeQuery = true)
    List<PositionRow> findPositionsByAccountId(@Param("accountId") UUID accountId);
}
