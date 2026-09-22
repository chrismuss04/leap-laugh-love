package com.leap.leaplaughlove.account.position;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Repository interface for managing trading positions of accounts.
 * Provides methods to retrieve positions based on account ID.
 */
public interface PositionRepository extends JpaRepository<Position, PositionId> {

    /**
     * Interface representing a row of position data retrieved from the database.
     */
    interface PositionRow {
        /**
         * Retrieves the unique identifier of the instrument for this position row.
         * @return the instrument ID as a string
         */
        String getInstrumentId();

        /**
         * Retrieves the trading symbol of the instrument for this position row.
         * @return the symbol as a string
         */
        String getSymbol();

        /**
         * Retrieves the name of the instrument for this position row.
         * @return the instrument name as a string
         */
        String getInstrumentName();

        /**
         * Retrieves the asset class of the instrument for this position row.
         * @return the asset class as a string
         */
        String getAssetClass();

        /**
         * Retrieves the quantity of the position for this position row.
         * @return the quantity as a long
         */
        long getQuantity();

        /**
         * Retrieves the average cost of the position for this position row.
         * @return the average cost as a BigDecimal
         */
        BigDecimal getAvgCost();
    }

    /**
     * Retrieves the list of positions for the specified account ID.
     * @param accountId the unique identifier of the account
     * @return a list of position rows associated with the account
     */
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

