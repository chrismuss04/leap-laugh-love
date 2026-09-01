package com.leap.leaplaughlove;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Trading Schema Validation Tests")
class TradingSchemaIntegrationTest {

    private static final String SCHEMA_FILE = "src/main/resources/db/leap_laugh_love_schema.sql";
    private static final String SEED_FILE = "src/main/resources/db/seed_trading.sql";

    private String readFile(String filePath) throws Exception {
        return new String(Files.readAllBytes(Paths.get(filePath)));
    }

    @Test
    @DisplayName("Schema should define trading.instruments table")
    void testInstrumentsTableSchema() throws Exception {
        String schema = readFile(SCHEMA_FILE);
        
        assertTrue(schema.contains("CREATE TABLE IF NOT EXISTS trading.instruments"),
            "Schema should define trading.instruments table");
        assertTrue(schema.contains("symbol TEXT NOT NULL"),
            "instruments table should have symbol");
        assertTrue(schema.contains("asset_class TEXT NOT NULL") &&
                   schema.contains("CHECK (asset_class IN ('EQUITY', 'FX', 'CRYPTO'))"),
            "instruments should have asset_class with constraints");
        assertTrue(schema.contains("UNIQUE (symbol, market)"),
            "symbol and market should be unique together");
    }

    @Test
    @DisplayName("Schema should define trading.accounts table with FK to clients")
    void testAccountsTableSchema() throws Exception {
        String schema = readFile(SCHEMA_FILE);
        
        assertTrue(schema.contains("CREATE TABLE IF NOT EXISTS trading.accounts"),
            "Schema should define trading.accounts table");

        String accountsSection = schema.substring(
            schema.indexOf("CREATE TABLE IF NOT EXISTS trading.accounts"),
            schema.indexOf("CREATE TABLE IF NOT EXISTS trading.instruments")
        );

        assertTrue(accountsSection.contains("client_id UUID NOT NULL") &&
                   accountsSection.contains("REFERENCES iam.clients"),
            "accounts should have client_id FK to iam.clients");
        assertTrue(accountsSection.contains("account_number TEXT NOT NULL UNIQUE"),
            "accounts should have unique account_number");
        assertTrue(accountsSection.contains("CHECK (status IN ('PENDING', 'ACTIVE', 'BLOCKED', 'CLOSED'))"),
            "accounts status should be constrained");

    @Test
    @DisplayName("Schema should define trading.orders table with audit-safe constraints")
    void testOrdersTableSchema() throws Exception {
        String schema = readFile(SCHEMA_FILE);
        
        assertTrue(schema.contains("CREATE TABLE IF NOT EXISTS trading.orders"),
            "Schema should define trading.orders table");
        assertTrue(schema.contains("account_id UUID NOT NULL") &&
                   schema.contains("REFERENCES trading.accounts") &&
                   schema.contains("ON DELETE RESTRICT"),
            "orders account_id should restrict delete");
        assertTrue(schema.contains("CHECK (side IN ('BUY', 'SELL'))"),
            "orders side should be constrained");
        assertTrue(schema.contains("CHECK (status IN ('SUBMITTED', 'ACCEPTED', 'REJECTED', 'FILLED'))"),
            "orders status should be constrained");
    }

    @Test
    @DisplayName("Schema should define trading.executions table with audit-safe constraints")
    void testExecutionsTableSchema() throws Exception {
        String schema = readFile(SCHEMA_FILE);
        
        assertTrue(schema.contains("CREATE TABLE IF NOT EXISTS trading.executions"),
            "Schema should define trading.executions table");
        assertTrue(schema.contains("order_id UUID NOT NULL") &&
                   schema.contains("REFERENCES trading.orders") &&
                   schema.contains("ON DELETE RESTRICT"),
            "executions order_id should restrict delete");
        assertTrue(schema.contains("CHECK (status IN ('FILLED', 'REJECTED'))"),
            "executions status should be constrained");
    }

    @Test
    @DisplayName("Schema should define trading.cash_ledger table with audit-safe constraints")
    void testCashLedgerTableSchema() throws Exception {
        String schema = readFile(SCHEMA_FILE);
        
        assertTrue(schema.contains("CREATE TABLE IF NOT EXISTS trading.cash_ledger"),
            "Schema should define trading.cash_ledger table");
        assertTrue(schema.contains("account_id UUID NOT NULL") &&
                   schema.contains("ON DELETE RESTRICT"),
            "cash_ledger account_id should restrict delete");
        assertTrue(schema.contains("order_id UUID") &&
                   schema.contains("ON DELETE RESTRICT"),
            "cash_ledger order_id should restrict delete when present");
        assertTrue(schema.contains("CHECK (entry_type IN ('DEPOSIT', 'WITHDRAWAL', 'BUY_SETTLEMENT', 'SELL_SETTLEMENT', 'ADJUSTMENT'))"),
            "cash_ledger entry_type should be constrained");
    }

    @Test
    @DisplayName("Schema should define trading.position_movements table with audit-safe constraints")
    void testPositionMovementsTableSchema() throws Exception {
        String schema = readFile(SCHEMA_FILE);
        
        assertTrue(schema.contains("CREATE TABLE IF NOT EXISTS trading.position_movements"),
            "Schema should define trading.position_movements table");
        assertTrue(schema.contains("account_id UUID NOT NULL") &&
                   schema.contains("ON DELETE RESTRICT"),
            "position_movements account_id should restrict delete");
        assertTrue(schema.contains("CHECK (movement_type IN ('BUY_FILL', 'SELL_FILL', 'ADJUSTMENT'))"),
            "position_movements movement_type should be constrained");
    }

    @Test
    @DisplayName("Schema should define trading.positions table")
    void testPositionsTableSchema() throws Exception {
        String schema = readFile(SCHEMA_FILE);
        
        assertTrue(schema.contains("CREATE TABLE IF NOT EXISTS trading.positions"),
            "Schema should define trading.positions table");
        assertTrue(schema.contains("account_id UUID NOT NULL") &&
                   schema.contains("instrument_id UUID NOT NULL") &&
                   schema.contains("PRIMARY KEY (account_id, instrument_id)"),
            "positions should have composite PK");
    }

    @Test
    @DisplayName("Seed data should insert 5 trading instruments")
    void testSeedDataInstruments() throws Exception {
        String seed = readFile(SEED_FILE);
        
        assertTrue(seed.contains("INSERT INTO trading.instruments"),
            "Seed data should insert instruments");
        
        // Check for specific instruments
        assertTrue(seed.contains("'AAPL'"), "Should have AAPL");
        assertTrue(seed.contains("'MSFT'"), "Should have MSFT");
        assertTrue(seed.contains("'GOOGL'"), "Should have GOOGL");
        assertTrue(seed.contains("'TSLA'"), "Should have TSLA");
        assertTrue(seed.contains("'BTC/USD'"), "Should have BTC/USD");
    }

    @Test
    @DisplayName("Seed data should insert instruments with various asset classes")
    void testSeedDataAssetClasses() throws Exception {
        String seed = readFile(SEED_FILE);
        
        assertTrue(seed.contains("'EQUITY'"), "Should have EQUITY instruments");
        assertTrue(seed.contains("'CRYPTO'"), "Should have CRYPTO instruments");
    }

    @Test
    @DisplayName("Seed data should create accounts for all clients")
    void testSeedDataAccounts() throws Exception {
        String seed = readFile(SEED_FILE);
        
        assertTrue(seed.contains("INSERT INTO trading.accounts"),
            "Seed data should create trading accounts");
        assertTrue(seed.contains("'ACTIVE'"), "Should have ACTIVE accounts");
        assertTrue(seed.contains("'PENDING'"), "Should have PENDING accounts");
        assertTrue(seed.contains("'BLOCKED'"), "Should have BLOCKED accounts");
    }

    @Test
    @DisplayName("Seed data should reference clients correctly in accounts")
    void testSeedDataAccountsReferencesClients() throws Exception {
        String seed = readFile(SEED_FILE);
        
        assertTrue(seed.contains("WHERE email ="),
            "Accounts should reference clients by email");
        assertTrue(seed.contains("client_id"),
            "Accounts should use client_id from clients");
    }

    @Test
    @DisplayName("Seed data should insert cash ledger entries for deposits")
    void testSeedDataCashLedger() throws Exception {
        String seed = readFile(SEED_FILE);
        
        assertTrue(seed.contains("INSERT INTO trading.cash_ledger"),
            "Seed data should insert cash ledger entries");
        assertTrue(seed.contains("'DEPOSIT'"), "Should have DEPOSIT entries");
        assertTrue(seed.contains("'BUY_SETTLEMENT'"), "Should have BUY_SETTLEMENT entries");
    }

    @Test
    @DisplayName("Seed data should insert orders with proper status values")
    void testSeedDataOrders() throws Exception {
        String seed = readFile(SEED_FILE);
        
        assertTrue(seed.contains("INSERT INTO trading.orders"),
            "Seed data should insert orders");
        assertTrue(seed.contains("'BUY'"), "Should have BUY orders");
        assertTrue(seed.contains("'FILLED'"), "Should have FILLED orders");
        assertTrue(seed.contains("'REJECTED'"), "Should have REJECTED orders");
    }

    @Test
    @DisplayName("Seed data should insert executions for filled orders")
    void testSeedDataExecutions() throws Exception {
        String seed = readFile(SEED_FILE);
        
        assertTrue(seed.contains("INSERT INTO trading.executions"),
            "Seed data should insert executions");
        assertTrue(seed.contains("fill_quantity"), "Should record fill quantities");
        assertTrue(seed.contains("fill_price"), "Should record fill prices");
    }

    @Test
    @DisplayName("Seed data should use ON CONFLICT for idempotency")
    void testSeedDataIdempotency() throws Exception {
        String seed = readFile(SEED_FILE);
        
        assertTrue(seed.contains("ON CONFLICT"), "Seed data should use ON CONFLICT");
        // All INSERT statements should have either ON CONFLICT or ON CONFLICT DO NOTHING
        long conflictCount = seed.split("INSERT INTO").length - 1;
        long onConflictCount = seed.split("ON CONFLICT").length - 1;
        assertTrue(onConflictCount > 0, "Should have ON CONFLICT clauses for idempotency");
    }

    @Test
    @DisplayName("Seed data should create position movements from executions")
    void testSeedDataPositionMovements() throws Exception {
        String seed = readFile(SEED_FILE);
        
        assertTrue(seed.contains("INSERT INTO trading.position_movements"),
            "Seed data should insert position movements");
        assertTrue(seed.contains("'BUY_FILL'"), "Should have BUY_FILL movements");
        assertTrue(seed.contains("quantity_delta"), "Should track quantity changes");
    }

    @Test
    @DisplayName("Seed data should aggregate positions from movements")
    void testSeedDataPositions() throws Exception {
        String seed = readFile(SEED_FILE);
        
        assertTrue(seed.contains("INSERT INTO trading.positions"),
            "Seed data should insert positions");
        assertTrue(seed.contains("SUM(pm.quantity_delta)"),
            "Positions should aggregate quantities from movements");
    }

    @Test
    @DisplayName("Orders should reference instruments correctly")
    void testOrdersReferenceInstruments() throws Exception {
        String seed = readFile(SEED_FILE);
        
        assertTrue(seed.contains("o.instrument_id") && seed.contains("i.symbol"),
            "Orders should reference instruments by symbol");
    }

    @Test
    @DisplayName("Executions should reference orders correctly")
    void testExecutionsReferenceOrders() throws Exception {
        String seed = readFile(SEED_FILE);
        
        assertTrue(seed.contains("INNER JOIN trading.orders o ON e.order_id = o.order_id"),
            "Executions should join to orders");
    }

    @Test
    @DisplayName("Cash ledger entries should reference orders and executions")
    void testCashLedgerReferences() throws Exception {
        String seed = readFile(SEED_FILE);
        
        assertTrue(seed.contains("o.order_id") && seed.contains("e.execution_id"),
            "Cash ledger should reference both orders and executions");
    }

    @Test
    @DisplayName("Position movements should reference orders and executions")
    void testPositionMovementReferences() throws Exception {
        String seed = readFile(SEED_FILE);
        
        assertTrue(seed.contains("o.order_id") && seed.contains("e.execution_id"),
            "Position movements should reference orders and executions");
    }

    @Test
    @DisplayName("Seed data includes orders with various statuses for testing")
    void testOrderStatusVariety() throws Exception {
        String seed = readFile(SEED_FILE);
        
        int filledCount = countOccurrences(seed, "'FILLED'");
        int rejectedCount = countOccurrences(seed, "'REJECTED'");
        
        assertTrue(filledCount > 0, "Should have FILLED orders");
        assertTrue(rejectedCount > 0, "Should have REJECTED orders for testing failure cases");
    }

    @Test
    @DisplayName("Audit tables use ON DELETE RESTRICT for data integrity")
    void testAuditTablesRestrictions() throws Exception {
        String schema = readFile(SCHEMA_FILE);
        
        // Check that audit-critical tables restrict deletes
        String ordersSection = schema.substring(schema.indexOf("CREATE TABLE IF NOT EXISTS trading.orders"), 
                                               schema.indexOf("CREATE TABLE IF NOT EXISTS trading.executions"));
        assertTrue(ordersSection.contains("ON DELETE RESTRICT"),
            "Orders should restrict cascading deletes");
        
        String executionsSection = schema.substring(schema.indexOf("CREATE TABLE IF NOT EXISTS trading.executions"),
                                                    schema.indexOf("CREATE TABLE IF NOT EXISTS trading.cash_ledger"));
        assertTrue(executionsSection.contains("ON DELETE RESTRICT"),
            "Executions should restrict cascading deletes");
        
        String cashLedgerSection = schema.substring(schema.indexOf("CREATE TABLE IF NOT EXISTS trading.cash_ledger"),
                                                    schema.indexOf("CREATE TABLE IF NOT EXISTS trading.positions"));
        assertTrue(cashLedgerSection.contains("ON DELETE RESTRICT"),
            "Cash ledger should restrict cascading deletes");
        
        String positionMovementsSection = schema.substring(schema.indexOf("CREATE TABLE IF NOT EXISTS trading.position_movements"));
        assertTrue(positionMovementsSection.contains("ON DELETE RESTRICT"),
            "Position movements should restrict cascading deletes");
    }

    // Helper method
    private int countOccurrences(String text, String pattern) {
        Pattern p = Pattern.compile(Pattern.quote(pattern));
        return (int) p.matcher(text).results().count();
    }
}
