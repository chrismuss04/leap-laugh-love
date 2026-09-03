package com.leap.leaplaughlove;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Paths;

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
}
