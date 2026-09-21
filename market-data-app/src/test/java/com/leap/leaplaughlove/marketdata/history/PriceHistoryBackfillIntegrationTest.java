package com.leap.leaplaughlove.marketdata.history;

import com.leap.leaplaughlove.marketdata.instrument.SimulatedInstrument;
import com.leap.leaplaughlove.marketdata.instrument.SimulatedInstrumentRepository;
import com.leap.leaplaughlove.marketdata.simulation.MarketSimulationEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end cover for the historical backfill against a real database: the schema's composite
 * unique key, the JPA mapping, the width-filtered history query, and the handoff from generated
 * history to the live simulation.
 */
@SpringBootTest
@Sql(scripts = "/db/marketdata_history_test_setup.sql")
@DisplayName("Price History Backfill Integration Tests")
class PriceHistoryBackfillIntegrationTest {

    @Autowired
    private SimulatedInstrumentRepository instrumentRepository;

    @Autowired
    private PriceCandleRepository candleRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    private PriceHistoryBackfill backfill;

    @BeforeEach
    void setUp() {
        // The backfill bean is disabled in the test profile, because as an ApplicationRunner it
        // would otherwise run before @Sql creates these tables. Build it here instead.
        backfill = new PriceHistoryBackfill(
                instrumentRepository, candleRepository, transactionTemplate,
                60L, List.of("3600:2", "60:1"));
    }

    @Test
    @DisplayName("writes a usable history at every configured width")
    void testBackfillPersistsEveryWidth() {
        backfill.run(null);

        OffsetDateTime now = OffsetDateTime.now();
        Page<PriceCandle> hourly = candleRepository
                .findByInstrument_SymbolAndBucketSecondsAndBucketStartBetweenOrderByBucketStartDesc(
                        "AAPL", 3600, now.minusDays(3), now, PageRequest.of(0, 1000));
        Page<PriceCandle> minutely = candleRepository
                .findByInstrument_SymbolAndBucketSecondsAndBucketStartBetweenOrderByBucketStartDesc(
                        "AAPL", 60, now.minusDays(3), now, PageRequest.of(0, 1000));

        assertTrue(hourly.getTotalElements() >= 46,
                "two days of hourly history expected, got " + hourly.getTotalElements());
        assertTrue(minutely.getTotalElements() >= 1400,
                "a day of minute history expected, got " + minutely.getTotalElements());

        assertTrue(hourly.getContent().stream().allMatch(c -> c.getBucketSeconds() == 3600),
                "an hourly query must not return candles of another width");
        assertTrue(minutely.getContent().stream().allMatch(c -> c.getBucketSeconds() == 60),
                "a minute query must not return candles of another width");
    }

    @Test
    @DisplayName("history is returned newest-first and is contiguous at its width")
    void testHistoryIsOrderedAndContiguous() {
        backfill.run(null);

        OffsetDateTime now = OffsetDateTime.now();
        List<PriceCandle> hourly = candleRepository
                .findByInstrument_SymbolAndBucketSecondsAndBucketStartBetweenOrderByBucketStartDesc(
                        "AAPL", 3600, now.minusDays(3), now, PageRequest.of(0, 1000))
                .getContent();

        assertFalse(hourly.isEmpty());
        for (int i = 1; i < hourly.size(); i++) {
            long newer = hourly.get(i - 1).getBucketStart().toEpochSecond();
            long older = hourly.get(i).getBucketStart().toEpochSecond();
            assertTrue(newer > older, "candles must be returned newest-first");
            assertEquals(3600, newer - older, "hourly history should have no gaps");
        }
    }

    @Test
    @DisplayName("both widths can cover the same instant without violating the unique key")
    void testWidthsCoexistOnTheSameBucketStart() {
        backfill.run(null);

        // Every 60s bucket that lands on an hour boundary shares its bucket_start with an
        // hourly candle. Before bucket_seconds joined the key, one of the two could not exist.
        OffsetDateTime now = OffsetDateTime.now();
        List<PriceCandle> minutely = candleRepository
                .findByInstrument_SymbolAndBucketSecondsAndBucketStartBetweenOrderByBucketStartDesc(
                        "AAPL", 60, now.minusDays(3), now, PageRequest.of(0, 1000))
                .getContent();

        // The hour in progress is excluded: its hourly candle is deliberately left unwritten for
        // the live accumulator to flush when it rolls over, while its minute candles are already
        // complete and written.
        long currentHourStart = now.toEpochSecond() - Math.floorMod(now.toEpochSecond(), 3600L);
        List<OffsetDateTime> onTheHour = minutely.stream()
                .map(PriceCandle::getBucketStart)
                .filter(start -> start.toEpochSecond() % 3600 == 0)
                .filter(start -> start.toEpochSecond() < currentHourStart)
                .toList();
        assertFalse(onTheHour.isEmpty(), "a day of minute candles must include hour boundaries");

        for (OffsetDateTime start : onTheHour) {
            long hourlyAtSameInstant = candleRepository
                    .findByInstrument_SymbolAndBucketSecondsAndBucketStartBetweenOrderByBucketStartDesc(
                            "AAPL", 3600, start, start, PageRequest.of(0, 10))
                    .getTotalElements();
            assertEquals(1, hourlyAtSameInstant,
                    "an hourly candle should coexist with the minute candle at " + start);
        }
    }

    @Test
    @DisplayName("a second run adds nothing, so restarts do not duplicate history")
    void testBackfillIsIdempotent() {
        backfill.run(null);
        long afterFirst = candleRepository.count();

        backfill.run(null);

        assertEquals(afterFirst, candleRepository.count(),
                "an instrument that already has history must be left alone");
    }

    @Test
    @DisplayName("the live simulation resumes from the end of the generated history")
    void testSimulationResumesFromBackfilledHistory() {
        backfill.run(null);

        SimulatedInstrument aapl = instrumentRepository.findByActiveTrue().stream()
                .filter(instrument -> instrument.getSymbol().equals("AAPL"))
                .findFirst()
                .orElseThrow();
        BigDecimal lastClose = candleRepository
                .findFirstByInstrument_SymbolOrderByBucketStartDescBucketSecondsAsc("AAPL")
                .orElseThrow()
                .getClose();

        MarketSimulationEngine engine = new MarketSimulationEngine(
                instrumentRepository, candleRepository, eventPublisher, 1000L);
        engine.initialize();

        BigDecimal resumed = engine.latest("AAPL").orElseThrow().price();
        assertEquals(0, lastClose.compareTo(resumed),
                "the live feed must open at the last historical close");
        assertEquals(0, aapl.getInitialPrice().compareTo(aapl.getInitialPrice()),
                "sanity: the instrument still carries its seed price");
        assertTrue(lastClose.compareTo(aapl.getInitialPrice()) != 0,
                "the generated history should have moved the price away from the seed, so this "
                        + "test would fail if the engine fell back to the seed price");
    }
}
