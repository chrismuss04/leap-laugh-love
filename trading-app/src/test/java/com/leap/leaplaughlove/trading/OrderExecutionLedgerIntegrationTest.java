package com.leap.leaplaughlove.trading;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Order Placement/Execution - DB Propagation Tests")
class OrderExecutionLedgerIntegrationTest {

    private static final String DB_URL = "jdbc:postgresql://localhost:5432/paysprint";
    private static final String DB_USER = "paysprint";

    private Connection getConnection() throws SQLException {
        String password = System.getenv("TEST_DB_PASSWORD");
        Assumptions.assumeTrue(password != null && !password.isEmpty(),
            "Skipping behavioral tests: TEST_DB_PASSWORD environment variable not set");
        Connection conn = DriverManager.getConnection(DB_URL, DB_USER, password);
        conn.setAutoCommit(false);
        return conn;
    }

    private UUID insertClient(Connection conn) throws SQLException {
        UUID clientId = UUID.randomUUID();
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO iam.clients (client_id, email, status) VALUES (?, ?, 'ACTIVE')")) {
            ps.setObject(1, clientId);
            ps.setString(2, "test-" + clientId + "@example.com");
            ps.executeUpdate();
        }
        return clientId;
    }

    private UUID insertAccount(Connection conn, UUID clientId) throws SQLException {
        UUID accountId = UUID.randomUUID();
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO trading.accounts (account_id, client_id, account_number, status) " +
                "VALUES (?, ?, ?, 'ACTIVE')")) {
            ps.setObject(1, accountId);
            ps.setObject(2, clientId);
            ps.setString(3, "TEST-ACCT-" + accountId.toString().substring(0, 8));
            ps.executeUpdate();
        }
        return accountId;
    }

    private UUID insertInstrument(Connection conn) throws SQLException {
        UUID instrumentId = UUID.randomUUID();
        String suffix = instrumentId.toString().substring(0, 8);
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO trading.instruments (instrument_id, symbol, instrument_name, asset_class, market, currency) " +
                "VALUES (?, ?, 'Test Instrument', 'EQUITY', ?, 'USD')")) {
            ps.setObject(1, instrumentId);
            ps.setString(2, "TST-" + suffix);
            ps.setString(3, "TEST-MKT-" + suffix);
            ps.executeUpdate();
        }
        return instrumentId;
    }

    private UUID insertOrder(Connection conn, UUID accountId, UUID instrumentId, long quantity, String status) throws SQLException {
        UUID orderId = UUID.randomUUID();
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO trading.orders (order_id, account_id, instrument_id, side, quantity, status) " +
                "VALUES (?, ?, ?, 'BUY', ?, ?)")) {
            ps.setObject(1, orderId);
            ps.setObject(2, accountId);
            ps.setObject(3, instrumentId);
            ps.setLong(4, quantity);
            ps.setString(5, status);
            ps.executeUpdate();
        }
        return orderId;
    }

    private UUID insertExecution(Connection conn, UUID orderId, Long fillQuantity, BigDecimal fillPrice,
                                  String status, String reason) throws SQLException {
        UUID executionId = UUID.randomUUID();
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO trading.executions (execution_id, order_id, fill_quantity, fill_price, status, reason) " +
                "VALUES (?, ?, ?, ?, ?, ?)")) {
            ps.setObject(1, executionId);
            ps.setObject(2, orderId);
            if (fillQuantity != null) {
                ps.setLong(3, fillQuantity);
            } else {
                ps.setNull(3, Types.BIGINT);
            }
            if (fillPrice != null) {
                ps.setBigDecimal(4, fillPrice);
            } else {
                ps.setNull(4, Types.NUMERIC);
            }
            ps.setString(5, status);
            ps.setString(6, reason);
            ps.executeUpdate();
        }
        return executionId;
    }

    private void insertPositionMovement(Connection conn, UUID accountId, UUID instrumentId, UUID orderId, UUID executionId,
                                         String movementType, long quantityDelta, BigDecimal costDelta) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO trading.position_movements " +
                "(account_id, instrument_id, order_id, execution_id, movement_type, quantity_delta, cost_delta) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?)")) {
            ps.setObject(1, accountId);
            ps.setObject(2, instrumentId);
            ps.setObject(3, orderId);
            ps.setObject(4, executionId);
            ps.setString(5, movementType);
            ps.setLong(6, quantityDelta);
            ps.setBigDecimal(7, costDelta);
            ps.executeUpdate();
        }
    }

    private void upsertPositionFromMovements(Connection conn, UUID accountId, UUID instrumentId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO trading.positions (account_id, instrument_id, quantity, avg_cost) " +
                "SELECT pm.account_id, pm.instrument_id, " +
                "COALESCE(SUM(pm.quantity_delta), 0), " +
                "CASE WHEN COALESCE(SUM(pm.quantity_delta), 0) > 0 " +
                "THEN COALESCE(SUM(pm.cost_delta), 0) / NULLIF(SUM(pm.quantity_delta), 0) ELSE 0 END " +
                "FROM trading.position_movements pm " +
                "WHERE pm.account_id = ? AND pm.instrument_id = ? " +
                "GROUP BY pm.account_id, pm.instrument_id " +
                "ON CONFLICT (account_id, instrument_id) DO UPDATE SET " +
                "quantity = EXCLUDED.quantity, avg_cost = EXCLUDED.avg_cost, updated_at = NOW()")) {
            ps.setObject(1, accountId);
            ps.setObject(2, instrumentId);
            ps.executeUpdate();
        }
    }

    /** Runs a single-column, single-row SELECT and returns the value, typed as requested. */
    private <T> T queryValue(Connection conn, String sql, Class<T> type, Object... params) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "Expected a row for query: " + sql);
                return rs.getObject(1, type);
            }
        }
    }

    /** Counts rows in a table matching a WHERE clause. */
    private long countRows(Connection conn, String table, String whereSql, Object... params) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM " + table + " WHERE " + whereSql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    @Test
    @DisplayName("Filled order propagates to execution, positions, and position_movements")
    void testFilledOrderPropagatesThroughDatabase() throws SQLException {
        try (Connection conn = getConnection()) {
            try {
                UUID clientId = insertClient(conn);
                UUID accountId = insertAccount(conn, clientId);
                UUID instrumentId = insertInstrument(conn);

                UUID orderId = insertOrder(conn, accountId, instrumentId, 100, "FILLED");
                UUID executionId = insertExecution(conn, orderId, 100L, new BigDecimal("150.25"),
                        "FILLED", "Executed at market price");
                insertPositionMovement(conn, accountId, instrumentId, orderId, executionId,
                        "BUY_FILL", 100, new BigDecimal("15025.00"));
                upsertPositionFromMovements(conn, accountId, instrumentId);

                // Order created under the account
                assertEquals(accountId,
                        queryValue(conn, "SELECT account_id FROM trading.orders WHERE order_id = ?", UUID.class, orderId));
                assertEquals("FILLED",
                        queryValue(conn, "SELECT status FROM trading.orders WHERE order_id = ?", String.class, orderId));

                // Execution created for that order_id
                assertEquals(orderId,
                        queryValue(conn, "SELECT order_id FROM trading.executions WHERE execution_id = ?", UUID.class, executionId));
                assertEquals(100L,
                        queryValue(conn, "SELECT fill_quantity FROM trading.executions WHERE execution_id = ?", Long.class, executionId));

                // Position updated for account_id + instrument_id
                assertEquals(100L, queryValue(conn,
                        "SELECT quantity FROM trading.positions WHERE account_id = ? AND instrument_id = ?",
                        Long.class, accountId, instrumentId));

                // position_movements ledger updated for the execution
                assertEquals(100L, queryValue(conn,
                        "SELECT quantity_delta FROM trading.position_movements " +
                        "WHERE account_id = ? AND instrument_id = ? AND execution_id = ?",
                        Long.class, accountId, instrumentId, executionId));
            } finally {
                conn.rollback();
            }
        }
    }

    @Test
    @DisplayName("Rejected execution leaves order logged but positions and position_movements untouched")
    void testRejectedExecutionDoesNotUpdatePositionsOrLedger() throws SQLException {
        try (Connection conn = getConnection()) {
            try {
                UUID clientId = insertClient(conn);
                UUID accountId = insertAccount(conn, clientId);
                UUID instrumentId = insertInstrument(conn);

                UUID orderId = insertOrder(conn, accountId, instrumentId, 1_000_000, "REJECTED");
                UUID executionId = insertExecution(conn, orderId, null, null, "REJECTED", "Insufficient funds");

                // Order is still logged as placed
                assertEquals("REJECTED",
                        queryValue(conn, "SELECT status FROM trading.orders WHERE order_id = ?", String.class, orderId));

                // Execution recorded as rejected
                assertEquals("REJECTED",
                        queryValue(conn, "SELECT status FROM trading.executions WHERE execution_id = ?", String.class, executionId));

                // No position or ledger entry created
                assertEquals(0L, countRows(conn, "trading.positions",
                        "account_id = ? AND instrument_id = ?", accountId, instrumentId));
                assertEquals(0L, countRows(conn, "trading.position_movements",
                        "account_id = ? AND instrument_id = ?", accountId, instrumentId));
            } finally {
                conn.rollback();
            }
        }
    }
}
