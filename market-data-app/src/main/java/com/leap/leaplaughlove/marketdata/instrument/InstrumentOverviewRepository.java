package com.leap.leaplaughlove.marketdata.instrument;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;

/** Loads metadata and completed daily baselines in one database round trip. */
@Repository
public class InstrumentOverviewRepository {
    private final JdbcTemplate jdbc;

    /**
     * Constructs a new InstrumentOverviewRepository.
     * @param jdbc access to the market data schema
     */
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

    /** Loads every active instrument with a baseline; used for all-instrument ranking paths. */
    public List<Metadata> findAll(OffsetDateTime periodStart, int bucketSeconds) {
        return findMovers(periodStart, bucketSeconds, true, Integer.MAX_VALUE);
    }

    public Page findPage(OffsetDateTime periodStart, int bucketSeconds, int page, int size,
            String query, String asset, String sort, String direction) {
        String term = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        String assetClass = asset == null ? "" : asset.trim();
        String where = " FROM marketdata.instruments m LEFT JOIN trading.instruments t ON t.symbol = m.symbol "
                + "WHERE m.is_active = TRUE AND (LOWER(m.display_name) LIKE ? OR LOWER(m.symbol) LIKE ?) "
                + "AND (? = '' OR COALESCE(t.asset_class, 'UNCLASSIFIED') = ?) ";
        Long total = jdbc.queryForObject("SELECT COUNT(*)" + where, Long.class,
                "%" + term + "%", "%" + term + "%", assetClass, assetClass);
        if (sort != null && !"name".equals(sort)) {
            String candidatesSql = "SELECT m.symbol, m.display_name, t.asset_class, t.currency, "
                    + "COALESCE((SELECT c.close FROM marketdata.price_candles c WHERE c.instrument_id = m.instrument_id "
                    + "AND c.bucket_seconds = ? AND c.bucket_start <= ? ORDER BY c.bucket_start DESC LIMIT 1), "
                    + "(SELECT c.close FROM marketdata.price_candles c WHERE c.instrument_id = m.instrument_id "
                    + "AND c.bucket_seconds = 86400 AND c.bucket_start <= ? ORDER BY c.bucket_start DESC LIMIT 1)) AS baseline_price "
                    + where + " ORDER BY m.symbol ASC";
            List<Metadata> candidates = jdbc.query(candidatesSql, this::mapMetadata,
                    bucketSeconds, Timestamp.from(periodStart.minusSeconds(bucketSeconds).toInstant()),
                    Timestamp.from(periodStart.minusDays(1).toInstant()), "%" + term + "%", "%" + term + "%",
                    assetClass, assetClass);
            return new Page(candidates, total == null ? 0 : total);
        }
        String pageSql = "SELECT m.symbol, m.display_name, t.asset_class, t.currency, "
                + "COALESCE((SELECT c.close FROM marketdata.price_candles c WHERE c.instrument_id = m.instrument_id "
                + "AND c.bucket_seconds = ? AND c.bucket_start <= ? ORDER BY c.bucket_start DESC LIMIT 1), "
                + "(SELECT c.close FROM marketdata.price_candles c WHERE c.instrument_id = m.instrument_id "
                + "AND c.bucket_seconds = 86400 AND c.bucket_start <= ? ORDER BY c.bucket_start DESC LIMIT 1)) AS baseline_price "
                + where + " ORDER BY LOWER(m.display_name) "
                + ("descending".equalsIgnoreCase(direction) ? "DESC" : "ASC")
                + ", m.symbol ASC LIMIT ? OFFSET ?";
        List<Metadata> rows = jdbc.query(pageSql, this::mapMetadata, bucketSeconds,
                Timestamp.from(periodStart.minusSeconds(bucketSeconds).toInstant()),
                Timestamp.from(periodStart.minusDays(1).toInstant()), "%" + term + "%", "%" + term + "%",
                assetClass, assetClass, size, (long) page * size);
        return new Page(rows, total == null ? 0 : total);
    }

    private Metadata mapMetadata(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new Metadata(rs.getString("symbol"), rs.getString("display_name"), rs.getString("asset_class"),
                rs.getString("currency"), rs.getBigDecimal("baseline_price"));
    }

    public record Page(List<Metadata> rows, long total) {}

    public List<Metadata> findMovers(OffsetDateTime periodStart, int bucketSeconds, boolean gainers, int limit) {
        String order = gainers ? "DESC" : "ASC";
        String sql = "SELECT m.symbol, m.display_name, t.asset_class, t.currency, "
                + "COALESCE((SELECT c.close FROM marketdata.price_candles c WHERE c.instrument_id = m.instrument_id "
                + "AND c.bucket_seconds = ? AND c.bucket_start <= ? ORDER BY c.bucket_start DESC LIMIT 1), "
                + "(SELECT c.close FROM marketdata.price_candles c WHERE c.instrument_id = m.instrument_id "
                + "AND c.bucket_seconds = 86400 AND c.bucket_start <= ? ORDER BY c.bucket_start DESC LIMIT 1)) AS baseline_price "
                + "FROM marketdata.instruments m LEFT JOIN trading.instruments t ON t.symbol = m.symbol "
                + "WHERE m.is_active = TRUE ORDER BY baseline_price " + order + " NULLS LAST, m.symbol LIMIT ?";
        return jdbc.query(sql, this::mapMetadata, bucketSeconds,
                Timestamp.from(periodStart.minusSeconds(bucketSeconds).toInstant()),
                Timestamp.from(periodStart.minusDays(1).toInstant()), limit);
    }

    public List<Metadata> findMovers(OffsetDateTime periodStart, int bucketSeconds, boolean gainers) {
        return findMovers(periodStart, bucketSeconds, gainers, Integer.MAX_VALUE);
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
