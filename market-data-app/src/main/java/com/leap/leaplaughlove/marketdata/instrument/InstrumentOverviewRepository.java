package com.leap.leaplaughlove.marketdata.instrument;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.List;

/** Loads metadata and completed daily baselines in one database round trip. */
@Repository
public class InstrumentOverviewRepository {
    private final JdbcTemplate jdbc;

    public InstrumentOverviewRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Loads every active instrument with the close of its last daily candle on or before the
     * period start.
     * @param periodStart the start of the period the baseline is measured from
     * @return the instruments by symbol; baseline price is null where no earlier candle exists
     */
    public List<Metadata> findAll(OffsetDateTime periodStart) {
        return findAll(periodStart, 86400);
    }

    /**
     * Loads every active instrument with the close of its last candle of one width that ended by
     * the period start, falling back to a daily candle where that width has no history.
     * @param periodStart the start of the period the baseline is measured from
     * @param bucketSeconds the candle width to read the baseline from
     * @return the instruments by symbol; baseline price is null where no earlier candle exists
     */
    public List<Metadata> findAll(OffsetDateTime periodStart, int bucketSeconds) {
        // A candle must end by periodStart. The existing candle primary key
        // supports each backwards lookup. Schemas have separate UUIDs: join by symbol.
        return jdbc.query("""
                SELECT m.symbol, m.display_name, t.asset_class, t.currency,
                    COALESCE(
                        (SELECT c.close FROM marketdata.price_candles c
                         WHERE c.instrument_id = m.instrument_id AND c.bucket_seconds = ?
                           AND c.bucket_start <= ?
                         ORDER BY c.bucket_start DESC LIMIT 1),
                        (SELECT c.close FROM marketdata.price_candles c
                         WHERE c.instrument_id = m.instrument_id AND c.bucket_seconds = 86400
                           AND c.bucket_start <= ?
                         ORDER BY c.bucket_start DESC LIMIT 1)) AS baseline_price
                FROM marketdata.instruments m
                LEFT JOIN trading.instruments t ON t.symbol = m.symbol
                WHERE m.is_active = TRUE
                ORDER BY m.symbol
                """, (rs, row) -> new Metadata(rs.getString("symbol"), rs.getString("display_name"),
                rs.getString("asset_class"), rs.getString("currency"), rs.getBigDecimal("baseline_price")),
                bucketSeconds,
                Timestamp.from(periodStart.minusSeconds(bucketSeconds).toInstant()),
                Timestamp.from(periodStart.minusDays(1).toInstant()));
    }

    /**
     * An instrument's static details and its baseline price.
     * @param symbol the ticker symbol
     * @param name the display name
     * @param assetClass the asset class, or null if the instrument isn't in the trading schema
     * @param currency the currency, or null if the instrument isn't in the trading schema
     * @param baselinePrice the close the period's change is measured from, or null if unavailable
     */
    public record Metadata(String symbol, String name, String assetClass, String currency,
            BigDecimal baselinePrice) {}
}
