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

    public List<Metadata> findAll(OffsetDateTime periodStart) {
        // A daily candle must end by periodStart. The existing candle primary key
        // supports each backwards lookup. Schemas have separate UUIDs: join by symbol.
        return jdbc.query("""
                SELECT m.symbol, m.display_name, t.asset_class, t.currency,
                    (SELECT c.close FROM marketdata.price_candles c
                     WHERE c.instrument_id = m.instrument_id AND c.bucket_seconds = 86400
                       AND c.bucket_start <= ?
                     ORDER BY c.bucket_start DESC LIMIT 1) AS baseline_price
                FROM marketdata.instruments m
                LEFT JOIN trading.instruments t ON t.symbol = m.symbol
                WHERE m.is_active = TRUE
                ORDER BY m.symbol
                """, (rs, row) -> new Metadata(rs.getString("symbol"), rs.getString("display_name"),
                rs.getString("asset_class"), rs.getString("currency"), rs.getBigDecimal("baseline_price")),
                Timestamp.from(periodStart.minusDays(1).toInstant()));
    }

    public record Metadata(String symbol, String name, String assetClass, String currency,
            BigDecimal baselinePrice) {}
}
