package com.leap.leaplaughlove.iam;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Reporting Sectors Seed Validation Tests")
class ReportingSectorsSeedTest {

    private static final String SEED_FILE = "iam-app/src/main/resources/db/seed_reporting_sectors.sql";
    private static final String SP500_FILE = "iam-app/src/main/resources/db/seed_sp500_trading.sql";
    private static final String TRADING_FILE = "iam-app/src/main/resources/db/seed_trading.sql";
    private static final String SCHEMA_FILE = "iam-app/src/main/resources/db/leap_laugh_love_schema.sql";
    private static final String MIGRATION_FILE = "scripts/migrate-reporting-recaps.sql";
    private static final String COMPOSE_FILE = "docker-compose.yml";
    private static final String WINDOWS_SETUP_FILE = "scripts/setup-windows-db.ps1";

    private static final Set<String> SECTORS = Set.of(
            "Communication Services", "Consumer Discretionary", "Consumer Staples", "Energy", "Financials",
            "Health Care", "Industrials", "Information Technology", "Materials", "Real Estate", "Utilities",
            "Crypto");

    // Relative to the repo root; Maven runs module tests from iam-app/, an IDE may not.
    private String readFile(String filePath) throws Exception {
        Path path = Paths.get(filePath);
        if (!Files.exists(path)) {
            path = Paths.get("..", filePath);
        }
        return new String(Files.readAllBytes(path));
    }

    // symbol -> sector, from the seed's VALUES rows.
    private Map<String, String> seededSectors() throws Exception {
        Map<String, String> sectors = new LinkedHashMap<>();
        Matcher row = Pattern.compile("\\('([^']+)', '([^']+)'\\)").matcher(readFile(SEED_FILE));
        while (row.find()) {
            assertNull(sectors.put(row.group(1), row.group(2)), "Symbol seeded twice: " + row.group(1));
        }
        return sectors;
    }

    // Every tradable symbol the trading seeds insert.
    private Set<String> tradableSymbols() throws Exception {
        Set<String> symbols = new TreeSet<>();
        Pattern instrument = Pattern.compile("\\('([^']+)', '(?:[^']|'')*', '(?:EQUITY|FX|CRYPTO)'");
        for (String file : new String[] {SP500_FILE, TRADING_FILE}) {
            Matcher row = instrument.matcher(readFile(file));
            while (row.find()) {
                symbols.add(row.group(1));
            }
        }
        return symbols;
    }

    @Test
    @DisplayName("Sectors seed should run after the reporting seed")
    void testSeedIsMounted() throws Exception {
        String compose = readFile(COMPOSE_FILE);
        assertTrue(compose.contains("./iam-app/src/main/resources/db/seed_reporting_sectors.sql:"
                        + "/docker-entrypoint-initdb.d/09-seed-reporting-sectors.sql:ro"),
                "docker-compose.yml should mount seed_reporting_sectors.sql as 09");

        String setup = readFile(WINDOWS_SETUP_FILE);
        assertTrue(setup.indexOf("'seed_reporting_sectors.sql'") > setup.indexOf("'seed_reporting.sql'"),
                "setup-windows-db.ps1 should load seed_reporting_sectors.sql after seed_reporting.sql");
    }

    @Test
    @DisplayName("Sectors seed should be idempotent")
    void testSeedIsIdempotent() throws Exception {
        assertTrue(readFile(SEED_FILE).strip().endsWith("ON CONFLICT (symbol) DO UPDATE SET sector = EXCLUDED.sector;"),
                "A rerun should update sectors in place, not fail on existing symbols");
    }

    @Test
    @DisplayName("Every tradable symbol should have exactly one known sector")
    void testEveryTradableHasASector() throws Exception {
        Map<String, String> sectors = seededSectors();
        Set<String> tradables = tradableSymbols();

        assertEquals(504, tradables.size(), "503 S&P 500 constituents plus BTC/USD");
        assertEquals(tradables, sectors.keySet(), "The sectors seed should cover exactly the tradable symbols");
        sectors.forEach((symbol, sector) ->
                assertTrue(SECTORS.contains(sector), symbol + " has an unknown sector: " + sector));
        assertEquals("Information Technology", sectors.get("AAPL"));
        assertEquals("Crypto", sectors.get("BTC/USD"));
    }

    @Test
    @DisplayName("Migration should create the same recap tables as the schema file")
    void testMigrationMatchesSchema() throws Exception {
        String schema = readFile(SCHEMA_FILE);
        String migration = readFile(MIGRATION_FILE);

        for (String table : new String[] {"reporting.instrument_sectors", "reporting.client_recaps"}) {
            String create = "CREATE TABLE IF NOT EXISTS " + table + " (";
            assertTrue(schema.contains(create), "Schema should create " + table);
            assertTrue(migration.contains(create), "Migration should create " + table);
        }
    }
}
