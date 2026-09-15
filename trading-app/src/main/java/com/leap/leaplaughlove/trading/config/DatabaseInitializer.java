package com.leap.leaplaughlove.trading.config;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

@Component
public class DatabaseInitializer {

    private final DataSource dataSource;

    public DatabaseInitializer(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void initializeDatabase() {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            // Create schemas
            stmt.execute("CREATE SCHEMA IF NOT EXISTS iam");
            stmt.execute("CREATE SCHEMA IF NOT EXISTS trading");
            System.out.println("Database schemas initialized successfully");

            // Seed test data for order history testing
            seedTestData(stmt);
        } catch (SQLException e) {
            System.err.println("Error initializing database: " + e.getMessage());
            throw new RuntimeException("Failed to initialize database", e);
        }
    }

    private void seedTestData(Statement stmt) throws SQLException {
        try {
            // matches the client_id iam-app seeds for alice.johnson@leap.com
            String clientId = "00000000-0000-0000-0000-000000000001";
            String accountId = "00000000-0000-0000-0002-000000000001";

            // Check if test data already exists
            var rs = stmt.executeQuery("SELECT COUNT(*) FROM trading.accounts WHERE account_id = '" + accountId + "'");
            if (rs.next() && rs.getInt(1) > 0) {
                System.out.println("Test orders already exist, skipping seed");
                return;
            }

            stmt.execute("INSERT INTO trading.accounts (account_id, client_id, account_number, status, base_currency, trading_enabled, created_at) " +
                    "VALUES ('" + accountId + "', '" + clientId + "', 'ACC-1001', 'ACTIVE', 'USD', TRUE, CURRENT_TIMESTAMP)");

            String aaplId = "00000000-0000-0000-0003-000000000001";
            String msftId = "00000000-0000-0000-0003-000000000002";
            String googlId = "00000000-0000-0000-0003-000000000003";

            stmt.execute("INSERT INTO trading.instruments (instrument_id, symbol, name, asset_class) " +
                    "VALUES ('" + aaplId + "', 'AAPL', 'Apple Inc.', 'EQUITY')");
            stmt.execute("INSERT INTO trading.instruments (instrument_id, symbol, name, asset_class) " +
                    "VALUES ('" + msftId + "', 'MSFT', 'Microsoft Corporation', 'EQUITY')");
            stmt.execute("INSERT INTO trading.instruments (instrument_id, symbol, name, asset_class) " +
                    "VALUES ('" + googlId + "', 'GOOGL', 'Alphabet Inc.', 'EQUITY')");

            // Insert test orders
            String[] orderIds = new String[3];
            for (int i = 0; i < 3; i++) {
                orderIds[i] = "00000000-0000-0000-0001-00000000000" + (i + 1);
            }

            stmt.execute("INSERT INTO trading.orders (order_id, account_id, instrument_id, side, quantity, status, submitted_at, filled_at) " +
                    "VALUES ('" + orderIds[0] + "', '" + accountId + "', '" + aaplId + "', 'BUY', 100, 'FILLED', CURRENT_TIMESTAMP - INTERVAL '2' DAY, CURRENT_TIMESTAMP - INTERVAL '2' DAY)");

            stmt.execute("INSERT INTO trading.orders (order_id, account_id, instrument_id, side, quantity, status, submitted_at, filled_at) " +
                    "VALUES ('" + orderIds[1] + "', '" + accountId + "', '" + msftId + "', 'SELL', 50, 'FILLED', CURRENT_TIMESTAMP - INTERVAL '1' DAY, CURRENT_TIMESTAMP - INTERVAL '1' DAY)");

            stmt.execute("INSERT INTO trading.orders (order_id, account_id, instrument_id, side, quantity, status, submitted_at, filled_at) " +
                    "VALUES ('" + orderIds[2] + "', '" + accountId + "', '" + googlId + "', 'BUY', 25, 'FILLED', CURRENT_TIMESTAMP - INTERVAL '3' HOUR, CURRENT_TIMESTAMP - INTERVAL '2' HOUR)");

            // Insert executions for first order (2 fills)
            stmt.execute("INSERT INTO trading.executions (execution_id, order_id, fill_quantity, fill_price, executed_at) " +
                    "VALUES ('" + UUID.randomUUID() + "', '" + orderIds[0] + "', 50, 150.00, CURRENT_TIMESTAMP - INTERVAL '2' DAY - INTERVAL '30' MINUTE)");

            stmt.execute("INSERT INTO trading.executions (execution_id, order_id, fill_quantity, fill_price, executed_at) " +
                    "VALUES ('" + UUID.randomUUID() + "', '" + orderIds[0] + "', 50, 150.10, CURRENT_TIMESTAMP - INTERVAL '2' DAY - INTERVAL '15' MINUTE)");

            // Insert execution for second order
            stmt.execute("INSERT INTO trading.executions (execution_id, order_id, fill_quantity, fill_price, executed_at) " +
                    "VALUES ('" + UUID.randomUUID() + "', '" + orderIds[1] + "', 50, 330.00, CURRENT_TIMESTAMP - INTERVAL '1' DAY - INTERVAL '10' MINUTE)");

            // Insert execution for third order
            stmt.execute("INSERT INTO trading.executions (execution_id, order_id, fill_quantity, fill_price, executed_at) " +
                    "VALUES ('" + UUID.randomUUID() + "', '" + orderIds[2] + "', 25, 140.00, CURRENT_TIMESTAMP - INTERVAL '2' HOUR)");

            System.out.println("Test orders and executions seeded successfully");
        } catch (SQLException e) {
            System.err.println("Note: Test data seeding skipped: " + e.getMessage());
            // Don't throw - this is optional
        }
    }
}
