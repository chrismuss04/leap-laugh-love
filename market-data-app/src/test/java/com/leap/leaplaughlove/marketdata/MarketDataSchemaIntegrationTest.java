package com.leap.leaplaughlove.marketdata;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Market Data Schema Validation Tests")
class MarketDataSchemaIntegrationTest {

    private static final String SCHEMA_FILE = "src/main/resources/db/leap_laugh_love_schema.sql";
    private static final String SEED_FILE = "src/main/resources/db/seed_marketdata.sql";

    private String readFile(String filePath) throws Exception {
        return new String(Files.readAllBytes(Paths.get(filePath)));
    }

    @Test
    @DisplayName("Schema should define marketdata.instruments table")
    void testInstrumentsTableSchema() throws Exception {
        String schema = readFile(SCHEMA_FILE);

        assertTrue(schema.contains("CREATE TABLE IF NOT EXISTS marketdata.instruments"),
                "Schema should define marketdata.instruments table");
        assertTrue(schema.contains("symbol TEXT NOT NULL UNIQUE"),
                "instruments should have a unique symbol");
        assertTrue(schema.contains("drift NUMERIC(9,6) NOT NULL"),
                "instruments should have a drift parameter");
        assertTrue(schema.contains("volatility NUMERIC(9,6) NOT NULL"),
                "instruments should have a volatility parameter");
        assertTrue(schema.contains("rng_seed BIGINT NOT NULL"),
                "instruments should have a reproducibility seed");
    }

    @Test
    @DisplayName("Schema should define marketdata.price_candles table with FK to instruments")
    void testPriceCandlesTableSchema() throws Exception {
        String schema = readFile(SCHEMA_FILE);

        assertTrue(schema.contains("CREATE TABLE IF NOT EXISTS marketdata.price_candles"),
                "Schema should define marketdata.price_candles table");

        String candlesSection = schema.substring(
                schema.indexOf("CREATE TABLE IF NOT EXISTS marketdata.price_candles"));

        assertTrue(candlesSection.contains("REFERENCES marketdata.instruments") &&
                        candlesSection.contains("ON DELETE RESTRICT"),
                "price_candles should restrict cascading deletes from instruments");
        assertTrue(candlesSection.contains("UNIQUE (instrument_id, bucket_start)"),
                "price_candles should be unique per instrument per bucket");
        assertTrue(candlesSection.contains("open") && candlesSection.contains("high")
                        && candlesSection.contains("low") && candlesSection.contains("close"),
                "price_candles should store OHLC values");
    }

    @Test
    @DisplayName("Seed data should insert the five simulated instruments")
    void testSeedDataInstruments() throws Exception {
        String seed = readFile(SEED_FILE);

        assertTrue(seed.contains("INSERT INTO marketdata.instruments"),
                "Seed data should insert simulated instruments");
        assertTrue(seed.contains("'AAPL'"), "Should have AAPL");
        assertTrue(seed.contains("'MSFT'"), "Should have MSFT");
        assertTrue(seed.contains("'GOOGL'"), "Should have GOOGL");
        assertTrue(seed.contains("'TSLA'"), "Should have TSLA");
        assertTrue(seed.contains("'BTCUSD'"), "Should have BTCUSD");
    }

    @Test
    @DisplayName("Seed data should use ON CONFLICT for idempotency")
    void testSeedDataIdempotency() throws Exception {
        String seed = readFile(SEED_FILE);

        assertTrue(seed.contains("ON CONFLICT (symbol)"),
                "Seed data should be re-runnable via ON CONFLICT");
    }
}
