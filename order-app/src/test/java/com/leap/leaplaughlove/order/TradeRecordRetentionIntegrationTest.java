package com.leap.leaplaughlove.order;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;
import org.junit.jupiter.api.Assumptions;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Trade Record Archive - Retention Validation Tests")
class TradeRecordRetentionIntegrationTest {

    private static final String SCHEMA_FILE = "src/main/resources/db/leap_laugh_love_schema.sql";

    private String readFile(String filePath) throws Exception {
        return new String(Files.readAllBytes(Paths.get(filePath)));
    }

    @Test
    @DisplayName("Schema should define a reusable trigger function rejecting delete/update")
    void testRejectDeleteOrUpdateFunctionDefined() throws Exception {
        String schema = readFile(SCHEMA_FILE);

        assertTrue(schema.contains("CREATE OR REPLACE FUNCTION trading.reject_delete_or_update()"),
            "Schema should define the reject_delete_or_update trigger function");
        assertTrue(schema.contains("RAISE EXCEPTION"),
            "Trigger function should raise an exception to block the operation");
    }

    @Test
    @DisplayName("Orders must never be deleted once created")
    void testOrdersCannotBeDeleted() throws Exception {
        String schema = readFile(SCHEMA_FILE);

        assertTrue(schema.contains("CREATE TRIGGER trg_orders_no_delete") &&
                   schema.contains("BEFORE DELETE ON trading.orders"),
            "orders table should have a BEFORE DELETE trigger preventing deletion");
    }

    @Test
    @DisplayName("Executions must never be updated or deleted once created")
    void testExecutionsAreImmutable() throws Exception {
        String schema = readFile(SCHEMA_FILE);

        assertTrue(schema.contains("CREATE TRIGGER trg_executions_no_delete_or_update") &&
                   schema.contains("BEFORE UPDATE OR DELETE ON trading.executions"),
            "executions table should have a BEFORE UPDATE OR DELETE trigger");
    }

    @Test
    @DisplayName("Cash ledger entries must never be updated or deleted once created")
    void testCashLedgerIsImmutable() throws Exception {
        String schema = readFile(SCHEMA_FILE);

        assertTrue(schema.contains("CREATE TRIGGER trg_cash_ledger_no_delete_or_update") &&
                   schema.contains("BEFORE UPDATE OR DELETE ON trading.cash_ledger"),
            "cash_ledger table should have a BEFORE UPDATE OR DELETE trigger");
    }

    @Test
    @DisplayName("Position movements must never be updated or deleted once created")
    void testPositionMovementsAreImmutable() throws Exception {
        String schema = readFile(SCHEMA_FILE);

        assertTrue(schema.contains("CREATE TRIGGER trg_position_movements_no_delete_or_update") &&
                   schema.contains("BEFORE UPDATE OR DELETE ON trading.position_movements"),
            "position_movements table should have a BEFORE UPDATE OR DELETE trigger");
    }

    @Test
    @DisplayName("Clients must never be deleted once created")
    void testClientsCannotBeDeleted() throws Exception {
        String schema = readFile(SCHEMA_FILE);

        assertTrue(schema.contains("CREATE TRIGGER trg_clients_no_delete") &&
                   schema.contains("BEFORE DELETE ON iam.clients"),
            "clients table should have a BEFORE DELETE trigger preventing deletion");
    }

    @Test
    @DisplayName("Client profiles must never be deleted once created")
    void testClientProfileCannotBeDeleted() throws Exception {
        String schema = readFile(SCHEMA_FILE);

        assertTrue(schema.contains("CREATE TRIGGER trg_client_profile_no_delete") &&
                   schema.contains("BEFORE DELETE ON iam.client_profile"),
            "client_profile table should have a BEFORE DELETE trigger preventing deletion");
    }

    @Test
    @DisplayName("Client credentials must never be deleted once created")
    void testClientCredentialsCannotBeDeleted() throws Exception {
        String schema = readFile(SCHEMA_FILE);

        assertTrue(schema.contains("CREATE TRIGGER trg_client_credentials_no_delete") &&
                   schema.contains("BEFORE DELETE ON iam.client_credentials"),
            "client_credentials table should have a BEFORE DELETE trigger preventing deletion");
    }

    private static final String DB_URL = "jdbc:postgresql://localhost:5432/paysprint";
    private static final String DB_USER = "paysprint";

    private Connection getConnection() throws SQLException {
        String password = System.getenv("TEST_DB_PASSWORD");
        Assumptions.assumeTrue(password != null && !password.isEmpty(),
            "Skipping behavioral tests: TEST_DB_PASSWORD environment variable not set");
        return DriverManager.getConnection(DB_URL, DB_USER, password);
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

    private UUID insertOrder(Connection conn, UUID accountId, UUID instrumentId) throws SQLException {
        UUID orderId = UUID.randomUUID();
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO trading.orders (order_id, account_id, instrument_id, side, quantity, status) " +
                "VALUES (?, ?, ?, 'BUY', 100, 'FILLED')")) {
            ps.setObject(1, orderId);
            ps.setObject(2, accountId);
            ps.setObject(3, instrumentId);
            ps.executeUpdate();
        }
        return orderId;
    }

    @Test
    @DisplayName("Attempting to DELETE from trading.orders throws exception")
    void testDeleteOrderThrowsException() throws SQLException {
        try (Connection conn = getConnection()) {
            UUID testAccountId = insertAccount(conn, insertClient(conn));
            UUID testOrderId = insertOrder(conn, testAccountId, insertInstrument(conn));

            SQLException thrown = assertThrows(SQLException.class, () -> {
                try (PreparedStatement ps = conn.prepareStatement("DELETE FROM trading.orders WHERE order_id = ?")) {
                    ps.setObject(1, testOrderId);
                    ps.executeUpdate();
                }
            });

            assertTrue(thrown.getMessage().contains("immutable") || 
                      thrown.getMessage().contains("retention") ||
                      thrown.getMessage().contains("cannot be"),
                "Exception message should mention immutability/retention: " + thrown.getMessage());
        }
    }

    @Test
    @DisplayName("Attempting to UPDATE trading.executions throws exception")
    void testUpdateExecutionThrowsException() throws SQLException {
        try (Connection conn = getConnection()) {
            UUID testExecutionId = UUID.randomUUID();
            UUID testOrderId = insertOrder(conn, insertAccount(conn, insertClient(conn)), insertInstrument(conn));

            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO trading.executions (execution_id, order_id, fill_quantity, fill_price, status) " +
                    "VALUES (?, ?, 50, 123.45, 'FILLED')")) {
                ps.setObject(1, testExecutionId);
                ps.setObject(2, testOrderId);
                ps.executeUpdate();
            }

            SQLException thrown = assertThrows(SQLException.class, () -> {
                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE trading.executions SET fill_price = 150.00 WHERE execution_id = ?")) {
                    ps.setObject(1, testExecutionId);
                    ps.executeUpdate();
                }
            });

            assertTrue(thrown.getMessage().contains("immutable") || 
                      thrown.getMessage().contains("retention") ||
                      thrown.getMessage().contains("cannot be"),
                "Exception message should mention immutability/retention: " + thrown.getMessage());
        }
    }

    @Test
    @DisplayName("Attempting to DELETE from iam.clients throws exception")
    void testDeleteClientThrowsException() throws SQLException {
        try (Connection conn = getConnection()) {
            UUID testClientId = UUID.randomUUID();

            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO iam.clients (client_id, email, status) VALUES (?, ?, 'ACTIVE')")) {
                ps.setObject(1, testClientId);
                ps.setString(2, "test-" + System.currentTimeMillis() + "@example.com");
                ps.executeUpdate();
            }

            SQLException thrown = assertThrows(SQLException.class, () -> {
                try (PreparedStatement ps = conn.prepareStatement("DELETE FROM iam.clients WHERE client_id = ?")) {
                    ps.setObject(1, testClientId);
                    ps.executeUpdate();
                }
            });

            assertTrue(thrown.getMessage().contains("immutable") || 
                      thrown.getMessage().contains("retention") ||
                      thrown.getMessage().contains("cannot be"),
                "Exception message should mention immutability/retention: " + thrown.getMessage());
        }
    }
}

