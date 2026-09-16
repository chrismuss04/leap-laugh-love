package com.leap.leaplaughlove.iam.config;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

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
            var rs = stmt.executeQuery("SELECT COUNT(*) FROM iam.clients WHERE email = 'alice.johnson@leap.com'");
            if (rs.next() && rs.getInt(1) > 0) {
                System.out.println("Test data already exists, skipping seed");
                return;
            }
            
            // Insert test client
            String clientId = "00000000-0000-0000-0000-000000000001";
            stmt.execute("INSERT INTO iam.clients (client_id, email, phone, status, created_at) " +
                    "VALUES ('" + clientId + "', 'alice.johnson@leap.com', '555-0001', 'ACTIVE', NOW())");

            stmt.execute("INSERT INTO iam.client_profile (client_id, full_name, date_of_birth, ssn, address_line1, address_line2, city, state_region, postal_code, country_code, experience_level, initial_deposit_amount) " +
                    "VALUES ('" + clientId + "', 'Alice Johnson', '1990-05-15', '123-45-6789', '123 Main St', 'Apt 101', 'Boston', 'MA', '02101', 'US', 'INTERMEDIATE', 50000.00)");

            // Insert test credentials (bcrypt hash of 'Password123!')
            stmt.execute("INSERT INTO iam.client_credentials (client_id, password_hash, failed_attempts) " +
                    "VALUES ('" + clientId + "', " +
                    "'$2a$10$WTObEKdYwfKWFGAT0M/eCerhC//QbRP7INlKt4pkukjItmZBQdO5K', 0)");
            
            System.out.println("Test data seeded successfully");
        } catch (SQLException e) {
            System.err.println("Note: Test data seeding skipped (tables may not exist yet): " + e.getMessage());
            // Don't throw - this is optional
        }
    }
}
