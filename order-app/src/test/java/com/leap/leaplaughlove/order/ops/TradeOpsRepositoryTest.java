package com.leap.leaplaughlove.order.ops;

import com.leap.leaplaughlove.order.ops.TradeOpsRepository.ExecutionRecord;
import com.leap.leaplaughlove.order.ops.TradeOpsRepository.OrderRecord;
import com.leap.leaplaughlove.order.ops.TradeOpsRepository.TradeSearch;
import com.leap.leaplaughlove.order.order.Order;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@JdbcTest
@Import(TradeOpsRepository.class)
@Sql("/db/order_submission_test_setup.sql")
@DisplayName("TradeOpsRepository Tests")
class TradeOpsRepositoryTest {

    // Seeded by order_submission_test_setup.sql.
    private static final UUID OWNER_CLIENT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OWNER_ACCOUNT = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID OTHER_ACCOUNT = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final UUID AAPL = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaa0001");
    private static final UUID MSFT = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaa0002");

    private static final UUID FILLED_ORDER = UUID.fromString("0f000000-0000-0000-0000-000000000001");
    private static final UUID REJECTED_ORDER = UUID.fromString("0f000000-0000-0000-0000-000000000002");
    private static final UUID OTHER_ORDER = UUID.fromString("0f000000-0000-0000-0000-000000000003");
    private static final UUID EXECUTION = UUID.fromString("0e000000-0000-0000-0000-000000000001");

    @Autowired
    private TradeOpsRepository repository;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void seed() {
        insertOrder(FILLED_ORDER, OWNER_ACCOUNT, AAPL, "BUY", "FILLED", "2026-10-02T10:00:00Z", "150.00", "1.00");
        insertOrder(REJECTED_ORDER, OWNER_ACCOUNT, MSFT, "SELL", "REJECTED", "2026-10-05T10:00:00Z", null, null);
        insertOrder(OTHER_ORDER, OTHER_ACCOUNT, AAPL, "BUY", "SUBMITTED", "2026-10-07T10:00:00Z", null, null);
        jdbc.update("UPDATE trading.orders SET accepted_at = ?, filled_at = ? WHERE order_id = ?",
                at("2026-10-02T10:00:01Z"), at("2026-10-02T10:00:02Z"), FILLED_ORDER);
        jdbc.update("UPDATE trading.orders SET rejected_at = ?, rejection_reason = ? WHERE order_id = ?",
                at("2026-10-05T10:00:01Z"), "Insufficient position", REJECTED_ORDER);

        jdbc.update("""
                INSERT INTO trading.executions (execution_id, order_id, fill_quantity, fill_price, status, executed_at,
                    reason, quote_bid, quote_ask, quote_last, quote_timestamp, quote_exchange)
                VALUES (?, ?, 10, 150.05, 'FILLED', ?, 'Executed at market price', 149.95, 150.05, 150.00, ?, 'NASDAQ')
                """, EXECUTION, FILLED_ORDER, at("2026-10-02T10:00:01.500Z"), at("2026-10-02T10:00:00.900Z"));
        jdbc.update("""
                INSERT INTO trading.executions (execution_id, order_id, status, executed_at, reason)
                VALUES (?, ?, 'REJECTED', ?, 'Insufficient position')
                """, UUID.randomUUID(), REJECTED_ORDER, at("2026-10-05T10:00:01Z"));
        jdbc.update("""
                INSERT INTO trading.cash_ledger (cash_ledger_id, account_id, order_id, execution_id, entry_type, amount,
                    currency, created_at)
                VALUES (?, ?, ?, ?, 'BUY_SETTLEMENT', -1500.50, 'USD', ?)
                """, UUID.randomUUID(), OWNER_ACCOUNT, FILLED_ORDER, EXECUTION, at("2026-10-02T10:00:01.600Z"));
        jdbc.update("""
                INSERT INTO trading.position_movements (movement_id, account_id, instrument_id, order_id, execution_id,
                    movement_type, quantity_delta, cost_delta, created_at)
                VALUES (?, ?, ?, ?, ?, 'BUY_FILL', 10, 1500.50, ?)
                """, UUID.randomUUID(), OWNER_ACCOUNT, AAPL, FILLED_ORDER, EXECUTION, at("2026-10-02T10:00:01.700Z"));
    }

    private void insertOrder(UUID orderId, UUID accountId, UUID instrumentId, String side, String status,
                             String submittedAt, String quotedPrice, String maxSlippage) {
        jdbc.update("""
                INSERT INTO trading.orders (order_id, account_id, instrument_id, side, quantity, status, submitted_at,
                    quoted_price, max_slippage_pct)
                VALUES (?, ?, ?, ?, 10, ?, ?, ?, ?)
                """, orderId, accountId, instrumentId, side, status, at(submittedAt),
                quotedPrice == null ? null : new BigDecimal(quotedPrice),
                maxSlippage == null ? null : new BigDecimal(maxSlippage));
    }

    private static OffsetDateTime at(String time) {
        return OffsetDateTime.parse(time);
    }

    private static TradeSearch search() {
        return new TradeSearch(null, null, null, null, null, null, null, null, null, null);
    }

    private static List<UUID> ids(List<TradeSummary> trades) {
        return trades.stream().map(TradeSummary::orderId).toList();
    }

    @Test
    @DisplayName("Finds a client's trades by email, ignoring case, newest first")
    void searchByEmail() {
        List<TradeSummary> trades = repository.search(
                new TradeSearch(null, null, "OWNER@example.com", null, null, null, null, null, null, null));

        assertEquals(List.of(REJECTED_ORDER, FILLED_ORDER), ids(trades));
        TradeSummary filled = trades.get(1);
        assertEquals("owner@example.com", filled.clientEmail());
        assertEquals("ACC-OWNER-USD", filled.accountNumber());
        assertEquals("AAPL", filled.symbol());
        assertEquals("BUY", filled.side());
        assertEquals(10, filled.quantity());
        assertEquals("FILLED", filled.status());
        assertTrue(at("2026-10-02T10:00:00Z").isEqual(filled.submittedAt()));
    }

    @Test
    @DisplayName("Filters by order ID, client, account, symbol, side, status and submission time")
    void searchFilters() {
        assertEquals(List.of(OTHER_ORDER), ids(repository.search(new TradeSearch(OTHER_ORDER, null, null, null, null,
                null, null, null, null, null))));
        assertEquals(List.of(REJECTED_ORDER, FILLED_ORDER), ids(repository.search(new TradeSearch(null, OWNER_CLIENT,
                null, null, null, null, null, null, null, null))));
        assertEquals(List.of(OTHER_ORDER), ids(repository.search(new TradeSearch(null, null, null, OTHER_ACCOUNT, null,
                null, null, null, null, null))));
        assertEquals(List.of(OTHER_ORDER), ids(repository.search(new TradeSearch(null, null, null, null, "ACC-OTHER-USD",
                null, null, null, null, null))));
        assertEquals(List.of(OTHER_ORDER, FILLED_ORDER), ids(repository.search(new TradeSearch(null, null, null, null, null,
                "aapl", null, null, null, null))));
        assertEquals(List.of(REJECTED_ORDER), ids(repository.search(new TradeSearch(null, null, null, null, null,
                null, Order.Side.SELL, null, null, null))));
        assertEquals(List.of(FILLED_ORDER), ids(repository.search(new TradeSearch(null, null, null, null, null,
                null, null, Order.Status.FILLED, null, null))));
        assertEquals(List.of(REJECTED_ORDER), ids(repository.search(new TradeSearch(null, null, null, null, null,
                null, null, null, at("2026-10-03T00:00:00Z"), at("2026-10-06T00:00:00Z")))));
        assertEquals(List.of(FILLED_ORDER), ids(repository.search(new TradeSearch(null, null, "owner@example.com", null,
                null, "AAPL", null, null, null, null))));
        assertTrue(repository.search(new TradeSearch(null, null, "nobody@example.com", null, null,
                null, null, null, null, null)).isEmpty());
    }


    @Test
    @DisplayName("Loads an order with its lifecycle fields")
    void findOrder() {
        OrderRecord order = repository.findOrder(FILLED_ORDER).orElseThrow();

        assertEquals(FILLED_ORDER, order.summary().orderId());
        assertEquals("MARKET", order.orderType());
        assertTrue(at("2026-10-02T10:00:01Z").isEqual(order.acceptedAt()));
        assertTrue(at("2026-10-02T10:00:02Z").isEqual(order.filledAt()));
        assertNull(order.rejectedAt());
        assertEquals(0, new BigDecimal("150.00").compareTo(order.quotedPrice()));
        assertEquals(0, new BigDecimal("1.00").compareTo(order.maxSlippagePercent()));
        assertEquals("Insufficient position", repository.findOrder(REJECTED_ORDER).orElseThrow().rejectionReason());
        assertTrue(repository.findOrder(UUID.randomUUID()).isEmpty());
    }

    @Test
    @DisplayName("Loads executions with their stored quote, or none")
    void findExecutions() {
        ExecutionRecord filled = repository.findExecutions(FILLED_ORDER).get(0);
        assertEquals(EXECUTION, filled.executionId());
        assertEquals("FILLED", filled.status());
        assertEquals(10L, filled.fillQuantity());
        assertEquals(0, new BigDecimal("150.05").compareTo(filled.fillPrice()));
        assertEquals(0, new BigDecimal("149.95").compareTo(filled.quote().getBid()));
        assertEquals(0, new BigDecimal("150.05").compareTo(filled.quote().getAsk()));
        assertTrue(at("2026-10-02T10:00:00.900Z").isEqual(filled.quote().getQuotedAt()));
        assertEquals("NASDAQ", filled.quote().getExchange());

        ExecutionRecord rejected = repository.findExecutions(REJECTED_ORDER).get(0);
        assertEquals("REJECTED", rejected.status());
        assertNull(rejected.fillQuantity());
        assertNull(rejected.quote());
    }

    @Test
    @DisplayName("Loads the cash entries and position movements booked for an order")
    void findSettlement() {
        var cash = repository.findCashEntries(FILLED_ORDER);
        assertEquals(1, cash.size());
        assertEquals("BUY_SETTLEMENT", cash.get(0).entryType());
        assertEquals(0, new BigDecimal("-1500.50").compareTo(cash.get(0).amount()));
        assertEquals("USD", cash.get(0).currency());

        var movements = repository.findPositionMovements(FILLED_ORDER);
        assertEquals(1, movements.size());
        assertEquals("BUY_FILL", movements.get(0).movementType());
        assertEquals(10, movements.get(0).quantityDelta());

        assertTrue(repository.findCashEntries(REJECTED_ORDER).isEmpty());
        assertTrue(repository.findPositionMovements(REJECTED_ORDER).isEmpty());
    }

    @Test
    @DisplayName("Counts the orders a search matches, by outcome")
    void countByStatus() {
        insertOrder(UUID.randomUUID(), OWNER_ACCOUNT, AAPL, "BUY", "OPEN", "2026-10-03T10:00:00Z", null, null);
        insertOrder(UUID.randomUUID(), OWNER_ACCOUNT, AAPL, "BUY", "ACCEPTED", "2026-10-03T11:00:00Z", null, null);
        insertOrder(UUID.randomUUID(), OWNER_ACCOUNT, AAPL, "BUY", "CANCELLED", "2026-10-03T12:00:00Z", null, null);

        assertEquals(new OrderCounts(5, 1, 1, 2), repository.countByStatus(
                new TradeSearch(null, null, "owner@example.com", null, null, null, null, null, null, null)));
        assertEquals(new OrderCounts(6, 1, 1, 3), repository.countByStatus(search()));
        assertEquals(new OrderCounts(1, 0, 1, 0), repository.countByStatus(new TradeSearch(null, OWNER_CLIENT, null,
                null, null, null, null, null, at("2026-10-05T00:00:00Z"), at("2026-10-06T00:00:00Z"))));
        assertEquals(new OrderCounts(0, 0, 0, 0), repository.countByStatus(
                new TradeSearch(null, null, "nobody@example.com", null, null, null, null, null, null, null)));
    }

    @Test
    @DisplayName("Counts every match, not only the first page of search results")
    void countByStatusUnlimited() {
        for (int i = 0; i < TradeOpsRepository.MAX_SEARCH_RESULTS + 5; i++) {
            insertOrder(UUID.randomUUID(), OWNER_ACCOUNT, MSFT, "BUY", "FILLED", "2026-09-01T10:00:00Z", null, null);
        }

        assertEquals(TradeOpsRepository.MAX_SEARCH_RESULTS + 8, repository.countByStatus(search()).submitted());
    }

    @Test
    @DisplayName("Groups the period's rejections by reason, most frequent first, keeping those without one")
    void countRejectionsByReason() {
        insertRejected("2026-10-05T11:00:00Z", "Insufficient position", "2026-10-05T11:00:01Z");
        insertRejected("2026-10-05T12:00:00Z", "Market quote unavailable", "2026-10-05T12:00:01Z");
        insertRejected("2026-10-05T13:00:00Z", null, "2026-10-05T13:00:01Z");
        insertRejected("2026-09-01T10:00:00Z", "Market quote unavailable", "2026-09-01T10:00:01Z");

        List<RejectionCount> rejections = repository.countRejectionsByReason(
                at("2026-10-01T00:00:00Z"), at("2026-10-08T00:00:00Z"));

        assertEquals(3, rejections.size());
        assertEquals("Insufficient position", rejections.get(0).reason());
        assertEquals(2, rejections.get(0).count());
        assertTrue(at("2026-10-05T11:00:01Z").isEqual(rejections.get(0).lastRejectedAt()));
        assertEquals("Market quote unavailable", rejections.get(1).reason());
        assertEquals(1, rejections.get(1).count());
        assertNull(rejections.get(2).reason());
        assertEquals(1, rejections.get(2).count());
        assertTrue(repository.countRejectionsByReason(at("2026-10-06T00:00:00Z"), at("2026-10-08T00:00:00Z")).isEmpty());
    }

    @Test
    @DisplayName("Finds orders still waiting to execute across every client, oldest first, leaving out OPEN orders")
    void findAwaitingExecutionSince() {
        UUID accepted = UUID.randomUUID();
        insertOrder(accepted, OWNER_ACCOUNT, MSFT, "BUY", "ACCEPTED", "2026-10-06T10:00:00Z", null, null);
        insertOrder(UUID.randomUUID(), OWNER_ACCOUNT, MSFT, "BUY", "OPEN", "2026-10-01T10:00:00Z", null, null);
        insertOrder(UUID.randomUUID(), OWNER_ACCOUNT, MSFT, "BUY", "SUBMITTED", "2026-10-07T10:05:00Z", null, null);

        assertEquals(List.of(accepted, OTHER_ORDER), ids(repository.findAwaitingExecutionSince(at("2026-10-07T10:01:00Z"))));
        assertEquals("owner@example.com", repository.findAwaitingExecutionSince(at("2026-10-07T00:00:00Z")).get(0).clientEmail());
        assertTrue(repository.findAwaitingExecutionSince(at("2026-10-01T00:00:00Z")).isEmpty());
    }

    private void insertRejected(String submittedAt, String reason, String rejectedAt) {
        UUID orderId = UUID.randomUUID();
        insertOrder(orderId, OWNER_ACCOUNT, MSFT, "SELL", "REJECTED", submittedAt, null, null);
        jdbc.update("UPDATE trading.orders SET rejected_at = ?, rejection_reason = ? WHERE order_id = ?",
                at(rejectedAt), reason, orderId);
    }

    @Test
    @DisplayName("Returns at most the maximum number of trades")
    void searchLimit() {
        for (int i = 0; i < TradeOpsRepository.MAX_SEARCH_RESULTS + 5; i++) {
            insertOrder(UUID.randomUUID(), OWNER_ACCOUNT, MSFT, "BUY", "SUBMITTED", "2026-09-01T10:00:00Z", null, null);
        }

        assertEquals(TradeOpsRepository.MAX_SEARCH_RESULTS, repository.search(search()).size());
    }
}
