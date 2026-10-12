package com.leap.leaplaughlove.order.report;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Reads filled orders from reporting.orders, the read model reporting-etl loads from the
 * order-events topic. Read with plain SQL rather than JPA: order-app never writes this table.
 */
@Repository
public class ActivityReportRepository {

    // Rejected orders never traded, so only FILLED rows count. The range is half-open so a fill
    // at exactly midnight lands in one day only; completed_at is indexed for it.
    private static final String FILLS_SQL = """
            SELECT side, quantity, fill_price, completed_at
            FROM reporting.orders
            WHERE status = 'FILLED'
              AND fill_price IS NOT NULL
              AND completed_at >= :from
              AND completed_at < :to
            """;

    private final NamedParameterJdbcTemplate jdbc;

    /**
     * Constructs an instance of ActivityReportRepository.
     * @param jdbc the JDBC template used to query reporting.orders
     */
    public ActivityReportRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Finds the orders that filled within a time range.
     * @param from the start of the range, inclusive
     * @param to the end of the range, exclusive
     * @param instrumentId the instrument to restrict to, or null for all instruments
     * @return the fills in the range, in no particular order
     */
    public List<ReportedFill> findFills(OffsetDateTime from, OffsetDateTime to, UUID instrumentId) {
        var params = new MapSqlParameterSource()
                .addValue("from", from)
                .addValue("to", to);
        String sql = FILLS_SQL;
        if (instrumentId != null) {
            sql += "  AND instrument_id = :instrumentId\n";
            params.addValue("instrumentId", instrumentId);
        }
        return jdbc.query(sql, params, (rs, rowNum) -> new ReportedFill(
                rs.getString("side"),
                rs.getLong("quantity"),
                rs.getBigDecimal("fill_price"),
                rs.getObject("completed_at", OffsetDateTime.class)));
    }
}
