package com.leap.leaplaughlove.iam;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Reporting Seed Validation Tests")
class ReportingSeedIntegrationTest {

    private static final String SEED_FILE = "iam-app/src/main/resources/db/seed_reporting.sql";
    private static final String COMPOSE_FILE = "docker-compose.yml";
    private static final String WINDOWS_SETUP_FILE = "scripts/setup-windows-db.ps1";
    private static final String BACKFILL_FILE = "scripts/migrate-reporting-seed.sql";

    // Relative to the repo root; Maven runs module tests from iam-app/, an IDE may not.
    private String readFile(String filePath) throws Exception {
        Path path = Paths.get(filePath);
        if (!Files.exists(path)) {
            path = Paths.get("..", filePath);
        }
        return new String(Files.readAllBytes(path));
    }

    // The seed's SQL statements, without its comment lines.
    private List<String> statements(String sql) {
        String code = Arrays.stream(sql.split("\n"))
            .filter(line -> !line.strip().startsWith("--"))
            .reduce("", (a, b) -> a + b + "\n");
        return Arrays.stream(code.split(";")).map(String::strip).filter(s -> !s.isEmpty()).toList();
    }

    @Test
    @DisplayName("Reporting seed should run after every other seed, E2E's 07 included")
    void testSeedIsMounted() throws Exception {
        String compose = readFile(COMPOSE_FILE);
        assertTrue(compose.contains(
                "./iam-app/src/main/resources/db/seed_reporting.sql:/docker-entrypoint-initdb.d/08-seed-reporting.sql:ro"),
            "docker-compose.yml should mount seed_reporting.sql as 08");

        String setup = readFile(WINDOWS_SETUP_FILE);
        assertTrue(setup.indexOf("'seed_reporting.sql'") > setup.indexOf("'seed_sp500_trading.sql'"),
            "setup-windows-db.ps1 should load seed_reporting.sql after the other seeds");
    }

    @Test
    @DisplayName("Reporting seed should be idempotent")
    void testSeedIsIdempotent() throws Exception {
        List<String> statements = statements(readFile(SEED_FILE));

        assertEquals(2, statements.size(), "Reporting seed should insert clients, then rejected orders");
        for (String statement : statements) {
            assertTrue(statement.endsWith("ON CONFLICT (client_id) DO NOTHING")
                    || statement.endsWith("ON CONFLICT (order_id) DO NOTHING"),
                "Every reporting seed insert should skip rows already there: " + statement);
        }
    }

    @Test
    @DisplayName("Reporting seed should copy every seeded client's registration")
    void testSeedCopiesClients() throws Exception {
        String seed = readFile(SEED_FILE);

        assertTrue(seed.contains("INSERT INTO reporting.clients (client_id, registered_at)\n"
                + "SELECT c.client_id, c.created_at\n"
                + "FROM iam.clients c"),
            "Registrations should come from iam.clients, dated when each client was created");
    }

    @Test
    @DisplayName("Reporting seed should copy rejected orders, and leave filled ones to SeededFillService")
    void testSeedCopiesRejectedOrdersOnly() throws Exception {
        String seed = readFile(SEED_FILE);

        assertTrue(seed.contains("WHERE o.status = 'REJECTED' AND o.rejected_at IS NOT NULL"),
            "Only rejected orders should be copied");
        // Filled orders arrive via SeededFillService's events, with their fill price.
        for (String statement : statements(seed)) {
            assertFalse(statement.contains("'FILLED'"),
                "Reporting seed should not copy filled orders: " + statement);
        }
    }

    @Test
    @DisplayName("Backfill script should apply the same reporting statements")
    void testBackfillMatchesSeed() throws Exception {
        String backfill = readFile(BACKFILL_FILE);

        for (String statement : statements(readFile(SEED_FILE))) {
            assertTrue(backfill.contains(statement),
                "migrate-reporting-seed.sql should match seed_reporting.sql: " + statement);
        }
    }
}
