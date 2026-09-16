package com.leap.leaplaughlove.trading.config;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Makes sure the schemas this service writes to exist before it serves traffic, for databases
 * that were not created by db/leap_laugh_love_schema.sql (an already-populated volume, a
 * developer's local postgres). Test data is NOT seeded here: db/seed_*.sql owns that, and it
 * runs from the postgres entrypoint with the real column names and generated client ids.
 */
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
            stmt.execute("CREATE SCHEMA IF NOT EXISTS iam");
            stmt.execute("CREATE SCHEMA IF NOT EXISTS trading");
            System.out.println("Database schemas initialized successfully");
        } catch (SQLException e) {
            System.err.println("Error initializing database: " + e.getMessage());
            throw new RuntimeException("Failed to initialize database", e);
        }
    }
}
