package com.leap.leaplaughlove.marketdata.instrument;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.junit.jupiter.api.AfterEach;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import static org.assertj.core.api.Assertions.*;

class InstrumentOverviewRepositoryTest {
    private EmbeddedDatabase database;
    private JdbcTemplate jdbc;
    private InstrumentOverviewRepository repository;
    private final OffsetDateTime start = OffsetDateTime.parse("2026-10-12T00:00:00Z");
    private final String id = "00000000-0000-0000-0000-000000000001";

    @BeforeEach
    void setup() {
        database = new EmbeddedDatabaseBuilder().generateUniqueName(true)
                .setType(EmbeddedDatabaseType.H2).addScript("schema.sql").build();
        jdbc = new JdbcTemplate(database);
        jdbc.execute("CREATE SCHEMA trading");
        jdbc.execute("CREATE TABLE trading.instruments (symbol VARCHAR(32) PRIMARY KEY, asset_class VARCHAR(16), currency CHAR(3))");
        jdbc.update("INSERT INTO marketdata.instruments (instrument_id,symbol,display_name,initial_price,drift,volatility,rng_seed,is_active) VALUES (?, 'AAPL', 'Apple', 100, 0, 0, 1, TRUE)", id);
        repository = new InstrumentOverviewRepository(jdbc);
    }

    @AfterEach
    void teardown() { database.shutdown(); }

    @Test
    void selectsLatestCompletedDailyCandleAndJoinsMetadataBySymbol() {
        jdbc.update("INSERT INTO trading.instruments VALUES ('AAPL','EQUITY','USD')");
        candle(start.minusDays(2), 86400, 90);
        candle(start.minusDays(1), 86400, 100);
        candle(start, 86400, 999);
        candle(start.minusMinutes(1), 60, 888);
        var rows = repository.findAll(start);
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().baselinePrice()).isEqualByComparingTo("100");
        assertThat(rows.getFirst().assetClass()).isEqualTo("EQUITY");
        assertThat(rows.getFirst().currency()).isEqualTo("USD");
    }

    @Test
    void keepsActiveInstrumentWithoutMetadataOrHistory() {
        var row = repository.findAll(start).getFirst();
        assertThat(row.name()).isEqualTo("Apple");
        assertThat(row.assetClass()).isNull();
        assertThat(row.currency()).isNull();
        assertThat(row.baselinePrice()).isNull();
    }

    @Test
    void excludesInactiveInstruments() {
        jdbc.update("UPDATE marketdata.instruments SET is_active = FALSE");
        assertThat(repository.findAll(start)).isEmpty();
    }

    @Test
    void readsAFinerBaselineThatEndedByTheStartFallingBackToDaily() {
        candle(start.minusDays(1), 86400, 100);
        candle(start.minusMinutes(10), 300, 110);
        candle(start.minusMinutes(5), 300, 120);
        candle(start, 300, 999);
        assertThat(repository.findAll(start, 300).getFirst().baselinePrice()).isEqualByComparingTo("120");
        jdbc.update("DELETE FROM marketdata.price_candles WHERE bucket_seconds = 300");
        assertThat(repository.findAll(start, 300).getFirst().baselinePrice()).isEqualByComparingTo("100");
    }

    @Test
    void usesHistoricalBaselineForEarlierPeriod() {
        candle(start.minusDays(8), 86400, 70);
        candle(start.minusDays(1), 86400, 100);
        assertThat(repository.findAll(start.minusDays(7)).getFirst().baselinePrice())
                .isEqualByComparingTo("70");
    }

    private void candle(OffsetDateTime at, int width, int price) {
        jdbc.update("INSERT INTO marketdata.price_candles VALUES (?, ?, ?, ?, ?, ?, ?)",
                id, Timestamp.from(at.toInstant()), width, price, price, price, price);
    }
}
