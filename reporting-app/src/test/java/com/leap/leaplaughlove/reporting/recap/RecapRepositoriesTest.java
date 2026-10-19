package com.leap.leaplaughlove.reporting.recap;

import com.leap.leaplaughlove.reporting.recap.RecapFill.Side;
import com.leap.leaplaughlove.reporting.recap.RecapSnapshotRepository.StoredRecap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.DefaultTransactionDefinition;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs the recap SQL against a real Postgres loaded with the schema file: the upsert and JSONB are
 * Postgres-only. Skipped unless TEST_DB_PASSWORD is set, like AnalystReportRepositoryTest. Every
 * test runs inside one transaction that is rolled back afterwards; the snapshot repository's own
 * transaction joins it, since both work on the same DataSource, so nothing is ever committed.
 */
@DisplayName("Recap repositories against Postgres")
class RecapRepositoriesTest {

    private static final String DB_URL = System.getenv().getOrDefault("TEST_DB_URL", "jdbc:postgresql://localhost:5432/paysprint");
    private static final YearMonth SEP = YearMonth.of(2026, 9);
    private static final OffsetDateTime GENERATED = OffsetDateTime.parse("2026-10-01T00:30:00Z");

    private SingleConnectionDataSource dataSource;
    private DataSourceTransactionManager transactions;
    private TransactionStatus transaction;
    private JdbcTemplate jdbc;
    private RecapFillRepository fills;
    private RecapSnapshotRepository snapshots;

    private final UUID alice = UUID.randomUUID();
    private final UUID bob = UUID.randomUUID();
    private final UUID account = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        String password = System.getenv("TEST_DB_PASSWORD");
        Assumptions.assumeTrue(password != null && !password.isEmpty(),
                "Skipping: TEST_DB_PASSWORD environment variable not set");
        dataSource = new SingleConnectionDataSource(DB_URL, "paysprint", password, true);
        transactions = new DataSourceTransactionManager(dataSource);
        transaction = transactions.getTransaction(new DefaultTransactionDefinition());
        jdbc = new JdbcTemplate(dataSource);
        fills = new RecapFillRepository(new NamedParameterJdbcTemplate(dataSource));
        snapshots = new RecapSnapshotRepository(dataSource, Jackson2ObjectMapperBuilder.json().build());

        jdbc.update("DELETE FROM reporting.client_recaps");
        jdbc.update("DELETE FROM reporting.client_months");
        jdbc.update("DELETE FROM reporting.orders");
    }

    @AfterEach
    void tearDown() {
        if (transaction != null) {
            transactions.rollback(transaction);
        }
        if (dataSource != null) {
            dataSource.destroy();
        }
    }

    private void order(UUID client, String symbol, String side, long quantity, String status, String price,
                       String completedAt) {
        jdbc.update("""
                INSERT INTO reporting.orders (order_id, account_id, client_id, instrument_id, symbol, side, quantity,
                                              status, fill_price, submitted_at, completed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), account, client, UUID.randomUUID(), symbol, side, quantity, status,
                price == null ? null : new BigDecimal(price), OffsetDateTime.parse(completedAt),
                OffsetDateTime.parse(completedAt));
    }

    private void loadedOrder(String status, String completedAt, OffsetDateTime loadedAt) {
        jdbc.update("""
                INSERT INTO reporting.orders (order_id, account_id, client_id, instrument_id, symbol, side, quantity,
                                              status, fill_price, submitted_at, completed_at, loaded_at)
                VALUES (?, ?, ?, ?, 'AAPL', 'BUY', 1, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), account, alice, UUID.randomUUID(), status,
                "FILLED".equals(status) ? new BigDecimal("100") : null, OffsetDateTime.parse(completedAt),
                OffsetDateTime.parse(completedAt), loadedAt);
    }

    @Test
    @DisplayName("reads every client's fills up to the month's end, and only fills")
    void fillsThroughMonth() {
        order(alice, "AAPL", "BUY", 10, "FILLED", "100", "2026-08-03T14:00:00Z");
        order(alice, "AAPL", "SELL", 4, "FILLED", "120", "2026-09-30T23:59:59Z");
        order(alice, "MSFT", "BUY", 1, "REJECTED", null, "2026-09-10T14:00:00Z");
        order(alice, "MSFT", "BUY", 1, "FILLED", "300", "2026-10-01T00:00:00Z");     // October
        order(bob, "BTC/USD", "BUY", 1, "FILLED", "65000", "2026-09-15T14:00:00Z");

        Map<UUID, List<RecapFill>> byClient = fills.fillsThrough(SEP);

        assertEquals(2, byClient.get(alice).size());
        assertTrue(byClient.get(alice).contains(new RecapFill(account, "AAPL", Side.SELL, 4,
                new BigDecimal("120.000000"), OffsetDateTime.parse("2026-09-30T23:59:59Z"))));
        assertEquals("BTC/USD", byClient.get(bob).get(0).symbol());
        assertEquals(1, fills.fillsThrough(bob, SEP).size());
        assertTrue(fills.fillsThrough(UUID.randomUUID(), SEP).isEmpty());
    }

    @Test
    @DisplayName("reads the sector table")
    void sectors() {
        // Its own rows: CI's test database loads the schema without the seeds that fill this table.
        jdbc.update("DELETE FROM reporting.instrument_sectors");
        jdbc.update("INSERT INTO reporting.instrument_sectors (symbol, sector) VALUES (?, ?), (?, ?)",
                "AAPL", "Information Technology", "BTC/USD", "Crypto");

        assertEquals(Map.of("AAPL", "Information Technology", "BTC/USD", "Crypto"), fills.sectors());
    }

    @Test
    @DisplayName("stores a month's recaps as JSON, reads them back whole, and replaces them on a rerun")
    void storesAndReplaces() {
        RecapCalculator calculator = new RecapCalculator();
        RecapPayload first = calculator.calculate(SEP, List.of(), PriceBook.empty(), Map.of());
        RecapPayload rerun = calculator.calculate(SEP, List.of(new RecapFill(account, "AAPL", Side.BUY, 1,
                        new BigDecimal("100"), OffsetDateTime.parse("2026-09-02T14:00:00Z"))),
                new PriceBook(Map.of("AAPL", Map.of(LocalDate.of(2026, 9, 30), new BigDecimal("110")))),
                Map.of()).withTopPercent(1);

        snapshots.saveMonth(SEP, Map.of(alice, first, bob, first), GENERATED);
        snapshots.saveMonth(SEP, Map.of(alice, rerun), GENERATED.plusDays(1));

        StoredRecap stored = snapshots.find(alice, SEP).orElseThrow();
        assertEquals(RecapCalculator.SCHEMA_VERSION, stored.schemaVersion());
        assertEquals(GENERATED.plusDays(1).toInstant(), stored.generatedAt().toInstant());
        assertEquals(rerun, snapshots.payload(stored));
        assertEquals(first, snapshots.payload(snapshots.find(bob, SEP).orElseThrow()));
        assertTrue(snapshots.find(alice, SEP.minusMonths(1)).isEmpty());
    }

    @Test
    @DisplayName("a month is up to date once built, until a fill up to its end is loaded after the build")
    void upToDate() {
        RecapPayload quiet = new RecapCalculator().calculate(SEP, List.of(), PriceBook.empty(), Map.of());
        loadedOrder("FILLED", "2026-09-10T14:00:00Z", GENERATED.minusHours(1));

        assertFalse(snapshots.isUpToDate(SEP), "nothing built yet");
        snapshots.saveMonth(SEP, Map.of(alice, quiet), GENERATED);
        assertTrue(snapshots.isUpToDate(SEP));

        // Loaded after the build, but none a September recap covers.
        loadedOrder("FILLED", "2026-10-01T00:00:00Z", GENERATED.plusHours(1));
        loadedOrder("REJECTED", "2026-09-12T14:00:00Z", GENERATED.plusHours(1));
        assertTrue(snapshots.isUpToDate(SEP));

        // The ETL catches up on a September fill: September needs rebuilding.
        loadedOrder("FILLED", "2026-09-28T12:00:00Z", GENERATED.plusHours(1));
        assertFalse(snapshots.isUpToDate(SEP));

        snapshots.saveMonth(SEP, Map.of(alice, quiet), GENERATED.plusHours(2));
        assertTrue(snapshots.isUpToDate(SEP));
        assertFalse(snapshots.isUpToDate(SEP.plusMonths(1)));
    }

    @Test
    @DisplayName("a month built in an older JSON shape is out of date, so the catch-up run rebuilds it")
    void olderShapeIsOutOfDate() {
        RecapPayload quiet = new RecapCalculator().calculate(SEP, List.of(), PriceBook.empty(), Map.of());
        snapshots.saveMonth(SEP, Map.of(alice, quiet, bob, quiet), GENERATED);
        assertTrue(snapshots.isUpToDate(SEP));

        jdbc.update("UPDATE reporting.client_recaps SET schema_version = ? WHERE client_id = ?",
                RecapCalculator.SCHEMA_VERSION - 1, bob);
        assertFalse(snapshots.isUpToDate(SEP));
    }
}
