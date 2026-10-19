package com.leap.leaplaughlove.reporting.recap;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Reads what recaps are built from, through the read-only pool: clients' fills from
 * reporting.orders and the sector reference table. Only FILLED orders ever moved shares or cash, so
 * they are the only ones read.
 */
@Repository
public class RecapFillRepository {

    private static final String FILLS_SQL = """
            SELECT client_id, account_id, symbol, side, quantity, fill_price, completed_at
            FROM reporting.orders
            WHERE status = 'FILLED' AND completed_at < :before
            """;

    private static final String CLIENT_FILLS_SQL = FILLS_SQL + " AND client_id = :clientId";

    private static final String SECTORS_SQL = "SELECT symbol, sector FROM reporting.instrument_sectors";

    private static final RowMapper<RecapFill> FILL = (rs, row) -> new RecapFill(
            rs.getObject("account_id", UUID.class),
            rs.getString("symbol"),
            RecapFill.Side.valueOf(rs.getString("side")),
            rs.getLong("quantity"),
            rs.getBigDecimal("fill_price"),
            rs.getObject("completed_at", OffsetDateTime.class));

    private final NamedParameterJdbcTemplate jdbc;

    /**
     * Creates the repository.
     * @param jdbc runs queries on the read-only reporting pool
     */
    public RecapFillRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Every fill of every client up to the end of a month: each client who traded before the month
     * ended may hold something in it.
     * @param month the month being recapped
     * @return each client's fills, in no particular order
     */
    public Map<UUID, List<RecapFill>> fillsThrough(YearMonth month) {
        Map<UUID, List<RecapFill>> byClient = new LinkedHashMap<>();
        jdbc.query(FILLS_SQL, new MapSqlParameterSource("before", endOf(month)), rs -> {
            UUID clientId = rs.getObject("client_id", UUID.class);
            byClient.computeIfAbsent(clientId, id -> new ArrayList<>()).add(FILL.mapRow(rs, 0));
        });
        return byClient;
    }

    /**
     * One client's fills up to the end of a month.
     * @param clientId the client
     * @param month the month being recapped
     * @return the client's fills, in no particular order
     */
    public List<RecapFill> fillsThrough(UUID clientId, YearMonth month) {
        return jdbc.query(CLIENT_FILLS_SQL, new MapSqlParameterSource("before", endOf(month))
                .addValue("clientId", clientId), FILL);
    }

    /**
     * The GICS sector of every symbol that has one.
     * @return sector by trading symbol
     */
    public Map<String, String> sectors() {
        Map<String, String> sectors = new HashMap<>();
        jdbc.query(SECTORS_SQL, rs -> {
            sectors.put(rs.getString("symbol"), rs.getString("sector"));
        });
        return sectors;
    }

    /** The first instant after the month, in UTC. */
    private static OffsetDateTime endOf(YearMonth month) {
        return month.plusMonths(1).atDay(1).atStartOfDay().atOffset(ZoneOffset.UTC);
    }
}
