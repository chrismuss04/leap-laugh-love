package com.leap.leaplaughlove.order.report;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@JdbcTest
@Import(ActivityReportRepository.class)
@Sql("/db/reporting_orders_test_setup.sql")
@DisplayName("ActivityReportRepository Tests")
class ActivityReportRepositoryTest {

    private static final UUID AAPL = UUID.randomUUID();
    private static final UUID MSFT = UUID.randomUUID();
    private static final OffsetDateTime FROM = OffsetDateTime.parse("2026-10-01T00:00:00Z");
    private static final OffsetDateTime TO = OffsetDateTime.parse("2026-10-08T00:00:00Z");

    @Autowired
    private ActivityReportRepository repository;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void seed() {
        insert(AAPL, "AAPL", "BUY", 10, "FILLED", "100.000000", "2026-10-01T00:00:00Z");
        insert(AAPL, "AAPL", "SELL", 4, "FILLED", "150.000000", "2026-10-07T23:59:59Z");
        insert(MSFT, "MSFT", "BUY", 2, "FILLED", "300.000000", "2026-10-03T12:00:00Z");
        insert(AAPL, "AAPL", "BUY", 7, "REJECTED", null, "2026-10-02T12:00:00Z");
        insert(AAPL, "AAPL", "BUY", 1, "FILLED", "99.000000", "2026-09-30T23:59:59Z");
        insert(AAPL, "AAPL", "BUY", 1, "FILLED", "99.000000", "2026-10-08T00:00:00Z");
    }

    private void insert(UUID instrumentId, String symbol, String side, long quantity, String status,
                        String fillPrice, String completedAt) {
        OffsetDateTime completed = OffsetDateTime.parse(completedAt);
        jdbc.update("""
                INSERT INTO reporting.orders (order_id, account_id, client_id, instrument_id, symbol, side,
                    quantity, status, fill_price, submitted_at, completed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), instrumentId, symbol, side,
                quantity, status, fillPrice == null ? null : new BigDecimal(fillPrice), completed, completed);
    }

    @Test
    @DisplayName("Returns only FILLED orders inside the half-open range")
    void filledOrdersInRange() {
        List<ReportedFill> fills = repository.findFills(FROM, TO, null);

        assertEquals(3, fills.size());
        assertTrue(fills.stream().noneMatch(f -> f.quantity() == 7), "rejected order must be excluded");
        assertTrue(fills.stream().noneMatch(f -> f.quantity() == 1), "fills outside the range must be excluded");
    }

    @Test
    @DisplayName("Maps side, quantity, fill price and completion time")
    void mapsColumns() {
        ReportedFill fill = repository.findFills(FROM, TO, MSFT).get(0);

        assertEquals("BUY", fill.side());
        assertEquals(2, fill.quantity());
        assertEquals(0, new BigDecimal("300").compareTo(fill.fillPrice()));
        assertTrue(OffsetDateTime.parse("2026-10-03T12:00:00Z").isEqual(fill.completedAt()));
    }

    @Test
    @DisplayName("Restricts to one instrument when given")
    void instrumentFilter() {
        List<ReportedFill> fills = repository.findFills(FROM, TO, AAPL);

        assertEquals(2, fills.size());
    }

    @Test
    @DisplayName("Returns nothing for a period with no fills")
    void emptyPeriod() {
        assertTrue(repository.findFills(
                OffsetDateTime.parse("2025-01-01T00:00:00Z"),
                OffsetDateTime.parse("2025-01-08T00:00:00Z"), null).isEmpty());
    }
}
