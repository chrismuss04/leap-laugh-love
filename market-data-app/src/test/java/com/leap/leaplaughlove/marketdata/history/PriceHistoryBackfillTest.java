package com.leap.leaplaughlove.marketdata.history;

import com.leap.leaplaughlove.marketdata.instrument.SimulatedInstrument;
import com.leap.leaplaughlove.marketdata.instrument.SimulatedInstrumentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("PriceHistoryBackfill Unit Tests")
class PriceHistoryBackfillTest {

    @Mock
    private SimulatedInstrumentRepository instrumentRepository;

    @Mock
    private PriceCandleRepository candleRepository;

    @Mock
    private TransactionTemplate transactionTemplate;

    private SimulatedInstrument aapl;

    @BeforeEach
    void setUp() {
        aapl = new SimulatedInstrument(
                UUID.randomUUID(), "AAPL", "Apple Inc.",
                new BigDecimal("150.00"), new BigDecimal("0.07"), new BigDecimal("0.25"), 42L, true);
    }

    /**
     * Runs the callback the backfill hands to the transaction template, so the save it performs
     * is observable on the mocked repository.
     */
    @SuppressWarnings("unchecked")
    private void runTransactionsInline() {
        doAnswer(invocation -> {
            invocation.getArgument(0, Consumer.class).accept(null);
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());
    }

    @SuppressWarnings("unchecked")
    private List<PriceCandle> captureSavedCandles() {
        ArgumentCaptor<List<PriceCandle>> captor = ArgumentCaptor.forClass(List.class);
        verify(candleRepository).saveAll(captor.capture());
        return captor.getValue();
    }

    private PriceHistoryBackfill backfillWith(String tiers) {
        return new PriceHistoryBackfill(
                instrumentRepository, candleRepository, transactionTemplate, 60L, List.of(tiers.split(",")));
    }

    @Test
    @DisplayName("generates candles at every configured width, each reaching back its own lookback")
    void testGeneratesEveryConfiguredWidth() {
        when(instrumentRepository.findByActiveTrue()).thenReturn(List.of(aapl));
        when(candleRepository.existsByInstrument_InstrumentId(aapl.getInstrumentId())).thenReturn(false);
        runTransactionsInline();

        backfillWith("3600:2,60:1").run(null);

        Map<Integer, List<PriceCandle>> byWidth = captureSavedCandles().stream()
                .collect(Collectors.groupingBy(PriceCandle::getBucketSeconds));

        assertEquals(Set.of(60, 3600), byWidth.keySet(), "both configured widths should be written");
        // Two days of hourly buckets and one day of minute buckets, less the trailing in-progress
        // bucket of each and whatever partial bucket the lookback starts inside.
        int hourly = byWidth.get(3600).size();
        int minutely = byWidth.get(60).size();
        assertTrue(hourly >= 46 && hourly <= 48, "expected ~48 hourly candles, got " + hourly);
        assertTrue(minutely >= 1438 && minutely <= 1440,
                "expected ~1440 minute candles, got " + minutely);
    }

    @Test
    @DisplayName("every candle's OHLC is internally consistent")
    void testCandlesAreWellFormed() {
        when(instrumentRepository.findByActiveTrue()).thenReturn(List.of(aapl));
        when(candleRepository.existsByInstrument_InstrumentId(aapl.getInstrumentId())).thenReturn(false);
        runTransactionsInline();

        backfillWith("3600:1").run(null);

        List<PriceCandle> candles = captureSavedCandles();
        assertFalse(candles.isEmpty());
        for (PriceCandle candle : candles) {
            assertTrue(candle.getHigh().compareTo(candle.getLow()) >= 0, "high must not be below low");
            assertTrue(candle.getHigh().compareTo(candle.getOpen()) >= 0, "high must not be below open");
            assertTrue(candle.getHigh().compareTo(candle.getClose()) >= 0, "high must not be below close");
            assertTrue(candle.getLow().compareTo(candle.getOpen()) <= 0, "low must not be above open");
            assertTrue(candle.getLow().compareTo(candle.getClose()) <= 0, "low must not be above close");
            assertTrue(candle.getOpen().signum() > 0, "prices must be positive");
        }
    }

    @Test
    @DisplayName("buckets are aligned to their width and stop before the current bucket")
    void testBucketsAreAlignedAndStopBeforeNow() {
        when(instrumentRepository.findByActiveTrue()).thenReturn(List.of(aapl));
        when(candleRepository.existsByInstrument_InstrumentId(aapl.getInstrumentId())).thenReturn(false);
        runTransactionsInline();

        backfillWith("3600:1").run(null);

        long now = OffsetDateTime.now().toEpochSecond();
        for (PriceCandle candle : captureSavedCandles()) {
            assertEquals(0, candle.getBucketStart().toEpochSecond() % 3600,
                    "bucket starts must sit on a boundary of their width");
            assertTrue(candle.getBucketStart().toEpochSecond() < now,
                    "the backfill must not write buckets in the future");
        }
    }

    @Test
    @DisplayName("the generated path is determined by the instrument's seed")
    void testGeneratedHistoryIsDeterministic() {
        SimulatedInstrument sameSeed = new SimulatedInstrument(
                UUID.randomUUID(), "AAPL2", "Apple Inc. (same seed)",
                aapl.getInitialPrice(), aapl.getDrift(), aapl.getVolatility(), aapl.getRngSeed(), true);
        SimulatedInstrument otherSeed = new SimulatedInstrument(
                UUID.randomUUID(), "AAPL3", "Apple Inc. (other seed)",
                aapl.getInitialPrice(), aapl.getDrift(), aapl.getVolatility(), aapl.getRngSeed() + 1, true);

        when(instrumentRepository.findByActiveTrue()).thenReturn(List.of(aapl, sameSeed, otherSeed));
        when(candleRepository.existsByInstrument_InstrumentId(any())).thenReturn(false);
        runTransactionsInline();

        backfillWith("3600:1").run(null);

        ArgumentCaptor<List<PriceCandle>> captor = ArgumentCaptor.forClass(List.class);
        verify(candleRepository, times(3)).saveAll(captor.capture());
        List<List<BigDecimal>> closes = captor.getAllValues().stream()
                .map(batch -> batch.stream()
                        .sorted(Comparator.comparing(PriceCandle::getBucketStart))
                        .map(PriceCandle::getClose)
                        .toList())
                .toList();

        assertEquals(closes.get(0), closes.get(1),
                "the same seed and parameters must reproduce the same path");
        assertNotEquals(closes.get(0), closes.get(2),
                "a different seed must produce a different path");
        assertTrue(closes.get(0).stream().distinct().count() > 1,
                "the path should vary across buckets rather than sitting flat");
    }

    @Test
    @DisplayName("instruments that already have candles are left untouched")
    void testSkipsInstrumentsThatAlreadyHaveHistory() {
        when(instrumentRepository.findByActiveTrue()).thenReturn(List.of(aapl));
        when(candleRepository.existsByInstrument_InstrumentId(aapl.getInstrumentId())).thenReturn(true);

        backfillWith("3600:1").run(null);

        verify(candleRepository, never()).saveAll(any());
        verify(transactionTemplate, never()).executeWithoutResult(any());
    }

    @Test
    @DisplayName("a bucket width that is not a whole number of walk steps is rejected")
    void testRejectsMisalignedTierWidth() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> backfillWith("90:1"));
        assertTrue(thrown.getMessage().contains("multiple of step-seconds"));
    }

    @Test
    @DisplayName("a malformed tier specification is rejected")
    void testRejectsMalformedTierSpec() {
        assertThrows(IllegalArgumentException.class, () -> backfillWith("3600"));
        assertThrows(IllegalArgumentException.class, () -> backfillWith("3600:0"));
    }
}
