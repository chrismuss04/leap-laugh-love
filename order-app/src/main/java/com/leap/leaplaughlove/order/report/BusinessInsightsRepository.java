package com.leap.leaplaughlove.order.report;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Reads the business insight figures from the reporting read model: reporting.orders and
 * reporting.clients, both loaded by reporting-etl. Aggregated in SQL with plain JDBC; order-app
 * never writes these tables, and nothing here touches the trading schema.
 */
@Repository
public class BusinessInsightsRepository {

    // Every range is half-open, like the activity report's, so a row at exactly midnight lands in
    // one period only. Rejected orders never traded, so trading figures count FILLED rows only.
    private static final String FILLED_IN_RANGE = """
            FROM reporting.orders
            WHERE status = 'FILLED'
              AND fill_price IS NOT NULL
              AND completed_at >= :from
              AND completed_at < :to
            """;

    private static final String TRADING_SQL = """
            SELECT COUNT(*) AS trade_count, COALESCE(SUM(quantity * fill_price), 0) AS traded_value
            """ + FILLED_IN_RANGE;

    private static final String TOP_INSTRUMENTS_SQL = """
            SELECT symbol, COUNT(*) AS trade_count, SUM(quantity * fill_price) AS traded_value
            """ + FILLED_IN_RANGE + """
            GROUP BY symbol
            ORDER BY trade_count DESC, traded_value DESC, symbol
            LIMIT :limit
            """;

    // Any outcome counts: a client whose order was rejected or cancelled was still active.
    private static final String ACTIVE_CLIENTS_SQL = """
            SELECT COUNT(DISTINCT client_id)
            FROM reporting.orders
            WHERE completed_at >= :from
              AND completed_at < :to
            """;

    private static final String REGISTRATIONS_SQL = """
            SELECT COUNT(*)
            FROM reporting.clients
            WHERE registered_at >= :from
              AND registered_at < :to
            """;

    private final NamedParameterJdbcTemplate jdbc;

    /**
     * Constructs an instance of BusinessInsightsRepository.
     * @param jdbc the JDBC template used to query the reporting schema
     */
    public BusinessInsightsRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Reads the headline figures for a time range.
     * @param from the start of the range, inclusive
     * @param to the end of the range, exclusive
     * @return the figures; zero when nothing happened in the range
     */
    public InsightFigures findFigures(OffsetDateTime from, OffsetDateTime to) {
        var params = range(from, to);
        long registrations = count(REGISTRATIONS_SQL, params);
        long activeClients = count(ACTIVE_CLIENTS_SQL, params);
        return jdbc.queryForObject(TRADING_SQL, params, (rs, rowNum) -> {
            long tradeCount = rs.getLong("trade_count");
            BigDecimal tradedValue = rs.getBigDecimal("traded_value");
            BigDecimal average = tradeCount == 0 ? null
                    : tradedValue.divide(BigDecimal.valueOf(tradeCount), 2, RoundingMode.HALF_UP);
            return new InsightFigures(tradedValue, tradeCount, average, registrations, activeClients);
        });
    }

    /**
     * Finds the instruments with the most filled orders in a time range.
     * @param from the start of the range, inclusive
     * @param to the end of the range, exclusive
     * @param limit the most instruments to return
     * @return the instruments, most trades first, then highest traded value, then by symbol
     */
    public List<InstrumentActivity> findTopInstruments(OffsetDateTime from, OffsetDateTime to, int limit) {
        return jdbc.query(TOP_INSTRUMENTS_SQL, range(from, to).addValue("limit", limit),
                (rs, rowNum) -> new InstrumentActivity(
                        rs.getString("symbol"),
                        rs.getLong("trade_count"),
                        rs.getBigDecimal("traded_value")));
    }

    private long count(String sql, MapSqlParameterSource params) {
        Long count = jdbc.queryForObject(sql, params, Long.class);
        return count == null ? 0 : count;
    }

    private static MapSqlParameterSource range(OffsetDateTime from, OffsetDateTime to) {
        return new MapSqlParameterSource().addValue("from", from).addValue("to", to);
    }
}
