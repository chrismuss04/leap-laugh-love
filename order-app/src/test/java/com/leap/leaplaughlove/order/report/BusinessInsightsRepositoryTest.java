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
@Import(BusinessInsightsRepository.class)
@Sql({"/db/reporting_orders_test_setup.sql", "/db/reporting_clients_test_setup.sql"})
@DisplayName("BusinessInsightsRepository Tests")
class BusinessInsightsRepositoryTest {

    private static final UUID ALICE = UUID.randomUUID();
    private static final UUID BOB = UUID.randomUUID();
    private static final UUID CAROL = UUID.randomUUID();
    private static final OffsetDateTime FROM = OffsetDateTime.parse("2026-10-01T00:00:00Z");
    private static final OffsetDateTime TO = OffsetDateTime.parse("2026-10-08T00:00:00Z");
    private static final OffsetDateTime EMPTY_FROM = OffsetDateTime.parse("2025-01-01T00:00:00Z");
    private static final OffsetDateTime EMPTY_TO = OffsetDateTime.parse("2025-01-08T00:00:00Z");

    @Autowired
    private BusinessInsightsRepository repository;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void seed() {
        // In the period: AAPL trades twice (1,600.00), MSFT and NVDA once each (600.00 and 90.00).
        order(ALICE, "AAPL", 10, "FILLED", "100.000000", "2026-10-01T00:00:00Z");
        order(ALICE, "AAPL", 4, "FILLED", "150.000000", "2026-10-07T23:59:59Z");
        order(BOB, "MSFT", 2, "FILLED", "300.000000", "2026-10-03T12:00:00Z");
        order(BOB, "NVDA", 1, "FILLED", "90.000000", "2026-10-04T12:00:00Z");
        // Carol only had an order rejected: active, but nothing traded.
        order(CAROL, "TSLA", 7, "REJECTED", null, "2026-10-02T12:00:00Z");
        // Either side of the period.
        order(UUID.randomUUID(), "TSLA", 1, "FILLED", "99.000000", "2026-09-30T23:59:59Z");
        order(UUID.randomUUID(), "TSLA", 1, "FILLED", "99.000000", "2026-10-08T00:00:00Z");

        client("2026-10-01T00:00:00Z");
        client("2026-10-05T09:30:00Z");
        client("2026-09-30T23:59:59Z");
        client("2026-10-08T00:00:00Z");
    }

    private void order(UUID clientId, String symbol, long quantity, String status, String fillPrice, String completedAt) {
        OffsetDateTime completed = OffsetDateTime.parse(completedAt);
        jdbc.update("""
                INSERT INTO reporting.orders (order_id, account_id, client_id, instrument_id, symbol, side,
                    quantity, status, fill_price, submitted_at, completed_at)
                VALUES (?, ?, ?, ?, ?, 'BUY', ?, ?, ?, ?, ?)
                """,
                UUID.randomUUID(), UUID.randomUUID(), clientId, UUID.randomUUID(), symbol,
                quantity, status, fillPrice == null ? null : new BigDecimal(fillPrice), completed, completed);
    }

    private void client(String registeredAt) {
        jdbc.update("INSERT INTO reporting.clients (client_id, registered_at) VALUES (?, ?)",
                UUID.randomUUID(), OffsetDateTime.parse(registeredAt));
    }

    @Test
    @DisplayName("Trading figures count only FILLED orders inside the half-open range")
    void tradingFigures() {
        InsightFigures figures = repository.findFigures(FROM, TO);

        assertEquals(4, figures.tradeCount());
        assertEquals(0, new BigDecimal("2290").compareTo(figures.tradedValue()));
        assertEquals(new BigDecimal("572.50"), figures.averageOrderValue());
    }

    @Test
    @DisplayName("Active clients are distinct and include those whose orders didn't fill")
    void activeClients() {
        assertEquals(3, repository.findFigures(FROM, TO).activeClients());
    }

    @Test
    @DisplayName("Registrations count clients who signed up inside the half-open range")
    void registrations() {
        assertEquals(2, repository.findFigures(FROM, TO).newRegistrations());
    }

    @Test
    @DisplayName("A period with no activity has zero figures and no average")
    void emptyPeriod() {
        InsightFigures figures = repository.findFigures(EMPTY_FROM, EMPTY_TO);

        assertEquals(0, figures.tradeCount());
        assertEquals(0, BigDecimal.ZERO.compareTo(figures.tradedValue()));
        assertNull(figures.averageOrderValue());
        assertEquals(0, figures.newRegistrations());
        assertEquals(0, figures.activeClients());
        assertTrue(repository.findTopInstruments(EMPTY_FROM, EMPTY_TO, 3).isEmpty());
    }

    @Test
    @DisplayName("Top instruments rank by trades, then traded value, and stop at the limit")
    void topInstruments() {
        List<InstrumentActivity> top = repository.findTopInstruments(FROM, TO, 2);

        assertEquals(List.of("AAPL", "MSFT"), top.stream().map(InstrumentActivity::symbol).toList());
        assertEquals(2, top.get(0).tradeCount());
        assertEquals(0, new BigDecimal("1600").compareTo(top.get(0).tradedValue()));
    }
}
