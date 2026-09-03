package com.leap.leaplaughlove;

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

// @DisplayName: sets the human-readable name JUnit shows in test reports/IDEs
// instead of the raw class/method name.
@DisplayName("Trade Record Archive - Retention Validation Tests")
class TradeRecordRetentionIntegrationTest {

    private static final String SCHEMA_FILE = "src/main/resources/db/leap_laugh_love_schema.sql";

    // Helper (not a test) that loads the schema file as plain text so each
    // test can search it for expected trigger/function definitions.
    private String readFile(String filePath) throws Exception {
        return new String(Files.readAllBytes(Paths.get(filePath)));
    }

    // @Test: marks this method as an executable JUnit test case.
    // Verifies the shared reject_delete_or_update() trigger function exists
    // and that it uses RAISE EXCEPTION to actually block the operation.
    @Test
    @DisplayName("Schema should define a reusable trigger function rejecting delete/update")
    void testRejectDeleteOrUpdateFunctionDefined() throws Exception {
        String schema = readFile(SCHEMA_FILE);

        assertTrue(schema.contains("CREATE OR REPLACE FUNCTION trading.reject_delete_or_update()"),
            "Schema should define the reject_delete_or_update trigger function");
        assertTrue(schema.contains("RAISE EXCEPTION"),
            "Trigger function should raise an exception to block the operation");
    }

    // Orders can still transition through statuses (SUBMITTED -> FILLED),
    // so only DELETE is blocked here, not UPDATE.
    @Test
    @DisplayName("Orders must never be deleted once created")
    void testOrdersCannotBeDeleted() throws Exception {
        String schema = readFile(SCHEMA_FILE);

        assertTrue(schema.contains("CREATE TRIGGER trg_orders_no_delete") &&
                   schema.contains("BEFORE DELETE ON trading.orders"),
            "orders table should have a BEFORE DELETE trigger preventing deletion");
    }

    // Executions are append-only audit records, so both UPDATE and DELETE
    // are blocked once a row is written.
    @Test
    @DisplayName("Executions must never be updated or deleted once created")
    void testExecutionsAreImmutable() throws Exception {
        String schema = readFile(SCHEMA_FILE);

        assertTrue(schema.contains("CREATE TRIGGER trg_executions_no_delete_or_update") &&
                   schema.contains("BEFORE UPDATE OR DELETE ON trading.executions"),
            "executions table should have a BEFORE UPDATE OR DELETE trigger");
    }

    // Same append-only rule as executions: cash ledger entries are a
    // financial audit trail and must never change after being written.
    @Test
    @DisplayName("Cash ledger entries must never be updated or deleted once created")
    void testCashLedgerIsImmutable() throws Exception {
        String schema = readFile(SCHEMA_FILE);

        assertTrue(schema.contains("CREATE TRIGGER trg_cash_ledger_no_delete_or_update") &&
                   schema.contains("BEFORE UPDATE OR DELETE ON trading.cash_ledger"),
            "cash_ledger table should have a BEFORE UPDATE OR DELETE trigger");
    }

    // Position movements are the append-only history log behind the live
    // trading.positions balances, so they're locked the same way.
    @Test
    @DisplayName("Position movements must never be updated or deleted once created")
    void testPositionMovementsAreImmutable() throws Exception {
        String schema = readFile(SCHEMA_FILE);

        assertTrue(schema.contains("CREATE TRIGGER trg_position_movements_no_delete_or_update") &&
                   schema.contains("BEFORE UPDATE OR DELETE ON trading.position_movements"),
            "position_movements table should have a BEFORE UPDATE OR DELETE trigger");
    }

    // Client identity records: status/other fields can still be updated
    // (e.g. PENDING -> ACTIVE -> LOCKED), so only DELETE is blocked.
    @Test
    @DisplayName("Clients must never be deleted once created")
    void testClientsCannotBeDeleted() throws Exception {
        String schema = readFile(SCHEMA_FILE);

        assertTrue(schema.contains("CREATE TRIGGER trg_clients_no_delete") &&
                   schema.contains("BEFORE DELETE ON iam.clients"),
            "clients table should have a BEFORE DELETE trigger preventing deletion");
    }

    // Profile fields (address, phone, etc.) can still be corrected via
    // UPDATE; only deletion of the profile row itself is blocked.
    @Test
    @DisplayName("Client profiles must never be deleted once created")
    void testClientProfileCannotBeDeleted() throws Exception {
        String schema = readFile(SCHEMA_FILE);

        assertTrue(schema.contains("CREATE TRIGGER trg_client_profile_no_delete") &&
                   schema.contains("BEFORE DELETE ON iam.client_profile"),
            "client_profile table should have a BEFORE DELETE trigger preventing deletion");
    }

    // Password hash/lockout state can still be updated (e.g. password
    // changes, failed-login tracking); only DELETE is blocked.
    @Test
    @DisplayName("Client credentials must never be deleted once created")
    void testClientCredentialsCannotBeDeleted() throws Exception {
        String schema = readFile(SCHEMA_FILE);

        assertTrue(schema.contains("CREATE TRIGGER trg_client_credentials_no_delete") &&
                   schema.contains("BEFORE DELETE ON iam.client_credentials"),
            "client_credentials table should have a BEFORE DELETE trigger preventing deletion");
    }

    // ========== BEHAVIORAL TESTS: Real Database DELETE/UPDATE Attempts ==========
    // These tests connect to the actual Postgres database and perform real
    // DELETE/UPDATE operations to verify the triggers actually throw exceptions.

    private static final String DB_URL = "jdbc:postgresql://localhost:5432/paysprint";
    private static final String DB_USER = "paysprint";

    private Connection getConnection() throws SQLException {
        String password = System.getenv("TEST_DB_PASSWORD");
        Assumptions.assumeTrue(password != null && !password.isEmpty(),
            "Skipping behavioral tests: TEST_DB_PASSWORD environment variable not set");
        return DriverManager.getConnection(DB_URL, DB_USER, password);
    }

    @Test
    @DisplayName("Attempting to DELETE from trading.orders throws exception")
    void testDeleteOrderThrowsException() throws SQLException {
        try (Connection conn = getConnection()) {
            UUID testOrderId = UUID.randomUUID();
            UUID testAccountId = UUID.randomUUID();
            UUID testInstrumentId = UUID.randomUUID();

            // First, ensure the account and instrument exist
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO trading.accounts (account_id, client_id, account_number, status) " +
                    "VALUES (?, ?, ?, 'ACTIVE') ON CONFLICT DO NOTHING")) {
                ps.setObject(1, testAccountId);
                ps.setObject(2, UUID.randomUUID());
                ps.setString(3, "TEST-ACCT-" + System.currentTimeMillis());
                ps.executeUpdate();
            }

            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO trading.instruments (instrument_id, symbol, market, asset_class, currency) " +
                    "VALUES (?, ?, ?, ?, ?) ON CONFLICT DO NOTHING")) {
                ps.setObject(1, testInstrumentId);
                ps.setString(2, "TEST");
                ps.setString(3, "NYSE");
                ps.setString(4, "EQUITY");
                ps.setString(5, "USD");
                ps.executeUpdate();
            }

            // Insert a test order
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO trading.orders (order_id, account_id, instrument_id, side, quantity, status) " +
                    "VALUES (?, ?, ?, 'BUY', 100, 'FILLED')")) {
                ps.setObject(1, testOrderId);
                ps.setObject(2, testAccountId);
                ps.setObject(3, testInstrumentId);
                ps.executeUpdate();
            }

            // Now attempt to delete it — this should throw an exception
            SQLException thrown = assertThrows(SQLException.class, () -> {
                try (PreparedStatement ps = conn.prepareStatement("DELETE FROM trading.orders WHERE order_id = ?")) {
                    ps.setObject(1, testOrderId);
                    ps.executeUpdate();
                }
            });

            // Verify the error message mentions the trigger/retention policy
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
            UUID testOrderId = UUID.randomUUID();

            // Insert a test execution
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO trading.executions (execution_id, order_id, fill_quantity, fill_price, status) " +
                    "VALUES (?, ?, 50, 123.45, 'FILLED')")) {
                ps.setObject(1, testExecutionId);
                ps.setObject(2, testOrderId);
                ps.executeUpdate();
            }

            // Now attempt to update it — this should throw an exception
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

            // Insert a test client
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO iam.clients (client_id, email, status) VALUES (?, ?, 'ACTIVE')")) {
                ps.setObject(1, testClientId);
                ps.setString(2, "test-" + System.currentTimeMillis() + "@example.com");
                ps.executeUpdate();
            }

            // Now attempt to delete it — this should throw an exception
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
