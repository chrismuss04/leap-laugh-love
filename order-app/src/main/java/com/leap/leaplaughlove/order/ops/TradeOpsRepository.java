package com.leap.leaplaughlove.order.ops;

import com.leap.leaplaughlove.order.execution.ExecutionQuote;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Read-only access to a trade's audit records across the trading tables and iam.clients.
 */
@Repository
public class TradeOpsRepository {

    static final int MAX_SEARCH_RESULTS = 100;

    private static final String TRADE_SELECT = """
            SELECT o.order_id, o.submitted_at, c.email, a.account_number, i.symbol, o.side, o.quantity,
                   o.status, o.accepted_at, o.rejected_at, o.filled_at, o.rejection_reason,
                   o.quoted_price, o.max_slippage_pct
            FROM trading.orders o
            JOIN trading.accounts a ON a.account_id = o.account_id
            JOIN trading.instruments i ON i.instrument_id = o.instrument_id
            JOIN iam.clients c ON c.client_id = a.client_id
            """;

    private final NamedParameterJdbcTemplate jdbc;

    /** Null fields aren't filtered on. Email and symbol ignore case; to is exclusive. */
    public record TradeSearch(UUID orderId, String clientEmail, String accountNumber, String symbol,
                              OffsetDateTime from, OffsetDateTime to) {}

    public record OrderRecord(TradeSummary summary, OffsetDateTime acceptedAt, OffsetDateTime rejectedAt,
                              OffsetDateTime filledAt, String rejectionReason, BigDecimal quotedPrice,
                              BigDecimal maxSlippagePercent) {}

    public record ExecutionRecord(UUID executionId, String status, Long fillQuantity, BigDecimal fillPrice,
                                  OffsetDateTime executedAt, String reason, ExecutionQuote quote) {}

    public record CashEntryRecord(String entryType, BigDecimal amount, String currency, OffsetDateTime createdAt) {}

    public record PositionMovementRecord(String movementType, long quantityDelta, BigDecimal costDelta,
                                         OffsetDateTime createdAt) {}

    public TradeOpsRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Newest first, up to {@link #MAX_SEARCH_RESULTS}. */
    public List<TradeSummary> search(TradeSearch search) {
        var params = new MapSqlParameterSource();
        List<String> conditions = new ArrayList<>();
        if (search.orderId() != null) {
            conditions.add("o.order_id = :orderId");
            params.addValue("orderId", search.orderId());
        }
        if (search.clientEmail() != null) {
            conditions.add("LOWER(c.email) = LOWER(:clientEmail)");
            params.addValue("clientEmail", search.clientEmail());
        }
        if (search.accountNumber() != null) {
            conditions.add("a.account_number = :accountNumber");
            params.addValue("accountNumber", search.accountNumber());
        }
        if (search.symbol() != null) {
            conditions.add("UPPER(i.symbol) = UPPER(:symbol)");
            params.addValue("symbol", search.symbol());
        }
        if (search.from() != null) {
            conditions.add("o.submitted_at >= :from");
            params.addValue("from", search.from());
        }
        if (search.to() != null) {
            conditions.add("o.submitted_at < :to");
            params.addValue("to", search.to());
        }
        String where = conditions.isEmpty() ? "" : "WHERE " + String.join(" AND ", conditions) + "\n";
        String sql = TRADE_SELECT + where
                + "ORDER BY o.submitted_at DESC, o.order_id DESC\nLIMIT " + MAX_SEARCH_RESULTS;
        return jdbc.query(sql, params, (rs, rowNum) -> toSummary(rs));
    }

    public Optional<OrderRecord> findOrder(UUID orderId) {
        List<OrderRecord> orders = jdbc.query(TRADE_SELECT + "WHERE o.order_id = :orderId",
                new MapSqlParameterSource("orderId", orderId),
                (rs, rowNum) -> new OrderRecord(
                        toSummary(rs),
                        rs.getObject("accepted_at", OffsetDateTime.class),
                        rs.getObject("rejected_at", OffsetDateTime.class),
                        rs.getObject("filled_at", OffsetDateTime.class),
                        rs.getString("rejection_reason"),
                        rs.getBigDecimal("quoted_price"),
                        rs.getBigDecimal("max_slippage_pct")));
        return orders.stream().findFirst();
    }

    public List<ExecutionRecord> findExecutions(UUID orderId) {
        return jdbc.query("""
                        SELECT execution_id, status, fill_quantity, fill_price, executed_at, reason,
                               quote_bid, quote_ask, quote_last, quote_timestamp, quote_exchange
                        FROM trading.executions
                        WHERE order_id = :orderId
                        ORDER BY executed_at, execution_id
                        """,
                new MapSqlParameterSource("orderId", orderId),
                (rs, rowNum) -> new ExecutionRecord(
                        rs.getObject("execution_id", UUID.class),
                        rs.getString("status"),
                        rs.getObject("fill_quantity", Long.class),
                        rs.getBigDecimal("fill_price"),
                        rs.getObject("executed_at", OffsetDateTime.class),
                        rs.getString("reason"),
                        toQuote(rs)));
    }

    public List<CashEntryRecord> findCashEntries(UUID orderId) {
        return jdbc.query("""
                        SELECT entry_type, amount, currency, created_at
                        FROM trading.cash_ledger
                        WHERE order_id = :orderId
                        ORDER BY created_at, cash_ledger_id
                        """,
                new MapSqlParameterSource("orderId", orderId),
                (rs, rowNum) -> new CashEntryRecord(
                        rs.getString("entry_type"),
                        rs.getBigDecimal("amount"),
                        rs.getString("currency"),
                        rs.getObject("created_at", OffsetDateTime.class)));
    }

    public List<PositionMovementRecord> findPositionMovements(UUID orderId) {
        return jdbc.query("""
                        SELECT movement_type, quantity_delta, cost_delta, created_at
                        FROM trading.position_movements
                        WHERE order_id = :orderId
                        ORDER BY created_at, movement_id
                        """,
                new MapSqlParameterSource("orderId", orderId),
                (rs, rowNum) -> new PositionMovementRecord(
                        rs.getString("movement_type"),
                        rs.getLong("quantity_delta"),
                        rs.getBigDecimal("cost_delta"),
                        rs.getObject("created_at", OffsetDateTime.class)));
    }

    private static TradeSummary toSummary(ResultSet rs) throws SQLException {
        return new TradeSummary(
                rs.getObject("order_id", UUID.class),
                rs.getObject("submitted_at", OffsetDateTime.class),
                rs.getString("email"),
                rs.getString("account_number"),
                rs.getString("symbol"),
                rs.getString("side"),
                rs.getLong("quantity"),
                rs.getString("status"));
    }

    // Null when no quote was stored.
    private static ExecutionQuote toQuote(ResultSet rs) throws SQLException {
        OffsetDateTime quotedAt = rs.getObject("quote_timestamp", OffsetDateTime.class);
        BigDecimal bid = rs.getBigDecimal("quote_bid");
        BigDecimal ask = rs.getBigDecimal("quote_ask");
        BigDecimal last = rs.getBigDecimal("quote_last");
        if (quotedAt == null && bid == null && ask == null && last == null) {
            return null;
        }
        return new ExecutionQuote(bid, ask, last, quotedAt, rs.getString("quote_exchange"));
    }
}
