package com.leap.leaplaughlove.order.ops;

import com.leap.leaplaughlove.order.execution.ExecutionQuote;
import com.leap.leaplaughlove.order.order.Order;
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

    private static final String TRADE_FROM = """
            FROM trading.orders o
            JOIN trading.accounts a ON a.account_id = o.account_id
            JOIN trading.instruments i ON i.instrument_id = o.instrument_id
            JOIN iam.clients c ON c.client_id = a.client_id
            """;

    private static final String TRADE_SELECT = """
            SELECT o.order_id, o.submitted_at, c.email, a.account_number, i.symbol, o.side, o.quantity,
                   o.status, o.accepted_at, o.rejected_at, o.filled_at, o.rejection_reason,
                   o.quoted_price, o.max_slippage_pct, o.order_type
            """ + TRADE_FROM;

    private final NamedParameterJdbcTemplate jdbc;

    /** Null fields aren't filtered on. Email and symbol ignore case; from is inclusive, to exclusive. */
    public record TradeSearch(UUID orderId, UUID clientId, String clientEmail, UUID accountId, String accountNumber,
                              String symbol, Order.Side side, Order.Status status,
                              OffsetDateTime from, OffsetDateTime to) {}

    public record OrderRecord(TradeSummary summary, String orderType, OffsetDateTime acceptedAt,
                              OffsetDateTime rejectedAt, OffsetDateTime filledAt, String rejectionReason,
                              BigDecimal quotedPrice, BigDecimal maxSlippagePercent) {}

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
        String sql = TRADE_SELECT + where(search, params)
                + "ORDER BY o.submitted_at DESC, o.order_id DESC\nLIMIT " + MAX_SEARCH_RESULTS;
        return jdbc.query(sql, params, (rs, rowNum) -> toSummary(rs));
    }

    /** Every order the search matches, not only the first {@link #MAX_SEARCH_RESULTS}. */
    public OrderCounts countByStatus(TradeSearch search) {
        var params = new MapSqlParameterSource();
        String sql = """
                SELECT COUNT(*) AS submitted,
                       COUNT(CASE WHEN o.status = 'FILLED' THEN 1 END) AS filled,
                       COUNT(CASE WHEN o.status = 'REJECTED' THEN 1 END) AS rejected,
                       COUNT(CASE WHEN o.status IN ('SUBMITTED', 'ACCEPTED', 'OPEN') THEN 1 END) AS awaiting
                """ + TRADE_FROM + where(search, params);
        return jdbc.queryForObject(sql, params, (rs, rowNum) -> new OrderCounts(
                rs.getLong("submitted"), rs.getLong("filled"), rs.getLong("rejected"), rs.getLong("awaiting")));
    }

    /**
     * Orders submitted in the period (from inclusive, to exclusive) and rejected, by reason: the
     * most frequent first, then by reason, with rejections recorded without one last.
     */
    public List<RejectionCount> countRejectionsByReason(OffsetDateTime from, OffsetDateTime to) {
        return jdbc.query("""
                        SELECT rejection_reason, COUNT(*) AS rejections, MAX(rejected_at) AS last_rejected_at
                        FROM trading.orders
                        WHERE status = 'REJECTED' AND submitted_at >= :from AND submitted_at < :to
                        GROUP BY rejection_reason
                        ORDER BY rejections DESC, rejection_reason NULLS LAST
                        """,
                new MapSqlParameterSource().addValue("from", from).addValue("to", to),
                (rs, rowNum) -> new RejectionCount(
                        rs.getString("rejection_reason"),
                        rs.getLong("rejections"),
                        rs.getObject("last_rejected_at", OffsetDateTime.class)));
    }

    /**
     * Orders still SUBMITTED or ACCEPTED that were submitted before the given time, across every
     * client, oldest first, up to {@link #MAX_SEARCH_RESULTS}. OPEN orders are left out: they wait
     * for their price by design.
     */
    public List<TradeSummary> findAwaitingExecutionSince(OffsetDateTime submittedBefore) {
        String sql = TRADE_SELECT + """
                WHERE o.status IN ('SUBMITTED', 'ACCEPTED') AND o.submitted_at < :submittedBefore
                ORDER BY o.submitted_at, o.order_id
                """ + "LIMIT " + MAX_SEARCH_RESULTS;
        return jdbc.query(sql, new MapSqlParameterSource("submittedBefore", submittedBefore),
                (rs, rowNum) -> toSummary(rs));
    }

    public Optional<OrderRecord> findOrder(UUID orderId) {
        List<OrderRecord> orders = jdbc.query(TRADE_SELECT + "WHERE o.order_id = :orderId",
                new MapSqlParameterSource("orderId", orderId),
                (rs, rowNum) -> new OrderRecord(
                        toSummary(rs),
                        rs.getString("order_type"),
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

    // Null fields aren't filtered on; adds a parameter for each one that is.
    private static String where(TradeSearch search, MapSqlParameterSource params) {
        List<String> conditions = new ArrayList<>();
        if (search.orderId() != null) {
            conditions.add("o.order_id = :orderId");
            params.addValue("orderId", search.orderId());
        }
        if (search.clientId() != null) {
            conditions.add("a.client_id = :clientId");
            params.addValue("clientId", search.clientId());
        }
        if (search.clientEmail() != null) {
            conditions.add("LOWER(c.email) = LOWER(:clientEmail)");
            params.addValue("clientEmail", search.clientEmail());
        }
        if (search.accountId() != null) {
            conditions.add("o.account_id = :accountId");
            params.addValue("accountId", search.accountId());
        }
        if (search.accountNumber() != null) {
            conditions.add("a.account_number = :accountNumber");
            params.addValue("accountNumber", search.accountNumber());
        }
        if (search.symbol() != null) {
            conditions.add("UPPER(i.symbol) = UPPER(:symbol)");
            params.addValue("symbol", search.symbol());
        }
        if (search.side() != null) {
            conditions.add("o.side = :side");
            params.addValue("side", search.side().name());
        }
        if (search.status() != null) {
            conditions.add("o.status = :status");
            params.addValue("status", search.status().name());
        }
        if (search.from() != null) {
            conditions.add("o.submitted_at >= :from");
            params.addValue("from", search.from());
        }
        if (search.to() != null) {
            conditions.add("o.submitted_at < :to");
            params.addValue("to", search.to());
        }
        return conditions.isEmpty() ? "" : "WHERE " + String.join(" AND ", conditions) + "\n";
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
