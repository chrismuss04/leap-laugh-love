package com.leap.leaplaughlove.marketdata.ingestion;

import com.leap.leaplaughlove.marketdata.instrument.SimulatedInstrument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DisplayName("LatestQuoteWriter Tests")
class LatestQuoteWriterTest {

    private JdbcTemplate jdbc;
    private LatestQuoteWriter writer;
    private SimulatedInstrument aapl;
    private SimulatedInstrument msft;

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", ""));
        jdbc.execute("CREATE SCHEMA marketdata");
        jdbc.execute("CREATE TABLE marketdata.quotes (quote_id UUID PRIMARY KEY, "
                + "instrument_id UUID NOT NULL UNIQUE, bid_price NUMERIC(18,6) NOT NULL, bid_size BIGINT NOT NULL, "
                + "ask_price NUMERIC(18,6) NOT NULL, ask_size BIGINT NOT NULL, last_price NUMERIC(18,6) NOT NULL, "
                + "last_size BIGINT NOT NULL, exchange VARCHAR(64) NOT NULL, sequence_number BIGINT NOT NULL, "
                + "quote_timestamp TIMESTAMP WITH TIME ZONE NOT NULL, received_at TIMESTAMP WITH TIME ZONE NOT NULL)");
        writer = new LatestQuoteWriter(jdbc);
        aapl = instrument("AAPL");
        msft = instrument("MSFT");
    }

    private static SimulatedInstrument instrument(String symbol) {
        return new SimulatedInstrument(UUID.randomUUID(), symbol, symbol, new BigDecimal("100.00"),
                new BigDecimal("0.07"), new BigDecimal("0.25"), 42L, true);
    }

    private static Quote quote(SimulatedInstrument instrument, String last, long sequence) {
        OffsetDateTime now = OffsetDateTime.now();
        BigDecimal price = new BigDecimal(last);
        return new Quote(instrument, price, 100, price, 100, price, 100, "SIMULATED", sequence, now, now);
    }

    @Test
    @DisplayName("keeps one row per instrument, replaced by each newer quote")
    void replacesEachInstrumentsRow() {
        writer.upsert(List.of(quote(aapl, "150.00", 1), quote(msft, "400.00", 1)));

        writer.upsert(List.of(quote(aapl, "151.00", 2)));

        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM marketdata.quotes", Integer.class));
        assertEquals(0, new BigDecimal("151.00").compareTo(jdbc.queryForObject(
                "SELECT last_price FROM marketdata.quotes WHERE instrument_id = ?", BigDecimal.class,
                aapl.getInstrumentId())));
        assertEquals(2L, jdbc.queryForObject(
                "SELECT sequence_number FROM marketdata.quotes WHERE instrument_id = ?", Long.class,
                aapl.getInstrumentId()));
        assertEquals(0, new BigDecimal("400.00").compareTo(jdbc.queryForObject(
                "SELECT last_price FROM marketdata.quotes WHERE instrument_id = ?", BigDecimal.class,
                msft.getInstrumentId())));
    }

    @Test
    @DisplayName("writes nothing for an empty batch")
    void emptyBatchIsANoOp() {
        jdbc.execute("DROP TABLE marketdata.quotes");
        writer.upsert(List.of());
    }
}
