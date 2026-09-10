package com.leap.leaplaughlove.config;

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
            // Check if test data already exists
            var rs = stmt.executeQuery("SELECT COUNT(*) FROM trading.orders WHERE client_id = '00000000-0000-0000-0000-000000000001'");
            if (rs.next() && rs.getInt(1) > 0) {
                System.out.println("Test orders already exist, skipping seed");
                return;
            }
            
            String clientId = "00000000-0000-0000-0000-000000000001";
            
            // Insert test orders
            String[] orderIds = new String[3];
            for (int i = 0; i < 3; i++) {
                orderIds[i] = "00000000-0000-0000-0001-00000000000" + (i + 1);
            }
            
            stmt.execute("INSERT INTO trading.orders (order_id, client_id, symbol, side, quantity, price, status, created_at, filled_at) " +
                    "VALUES ('" + orderIds[0] + "', '" + clientId + "', 'AAPL', 'BUY', 100, 150.00, 'FILLED', CURRENT_TIMESTAMP - INTERVAL '2' DAY, CURRENT_TIMESTAMP - INTERVAL '2' DAY)");
            
            stmt.execute("INSERT INTO trading.orders (order_id, client_id, symbol, side, quantity, price, status, created_at, filled_at) " +
                    "VALUES ('" + orderIds[1] + "', '" + clientId + "', 'MSFT', 'SELL', 50, 330.00, 'FILLED', CURRENT_TIMESTAMP - INTERVAL '1' DAY, CURRENT_TIMESTAMP - INTERVAL '1' DAY)");
            
            stmt.execute("INSERT INTO trading.orders (order_id, client_id, symbol, side, quantity, price, status, created_at, filled_at) " +
                    "VALUES ('" + orderIds[2] + "', '" + clientId + "', 'GOOGL', 'BUY', 25, 140.00, 'FILLED', CURRENT_TIMESTAMP - INTERVAL '3' HOUR, CURRENT_TIMESTAMP - INTERVAL '2' HOUR)");
            
            // Insert executions for first order (2 fills)
            stmt.execute("INSERT INTO trading.executions (execution_id, order_id, quantity, price, executed_at) " +
                    "VALUES ('" + UUID.randomUUID() + "', '" + orderIds[0] + "', 50, 150.00, CURRENT_TIMESTAMP - INTERVAL '2' DAY - INTERVAL '30' MINUTE)");
            
            stmt.execute("INSERT INTO trading.executions (execution_id, order_id, quantity, price, executed_at) " +
                    "VALUES ('" + UUID.randomUUID() + "', '" + orderIds[0] + "', 50, 150.10, CURRENT_TIMESTAMP - INTERVAL '2' DAY - INTERVAL '15' MINUTE)");
            
            // Insert execution for second order
            stmt.execute("INSERT INTO trading.executions (execution_id, order_id, quantity, price, executed_at) " +
                    "VALUES ('" + UUID.randomUUID() + "', '" + orderIds[1] + "', 50, 330.00, CURRENT_TIMESTAMP - INTERVAL '1' DAY - INTERVAL '10' MINUTE)");
            
            // Insert execution for third order
            stmt.execute("INSERT INTO trading.executions (execution_id, order_id, quantity, price, executed_at) " +
                    "VALUES ('" + UUID.randomUUID() + "', '" + orderIds[2] + "', 25, 140.00, CURRENT_TIMESTAMP - INTERVAL '2' HOUR)");
            
            System.out.println("Test orders and executions seeded successfully");
        } catch (SQLException e) {
            System.err.println("Note: Test data seeding skipped: " + e.getMessage());
            // Don't throw - this is optional
        }
    }
}
