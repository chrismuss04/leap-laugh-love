package com.leap.leaplaughlove.marketdata.history;

import com.leap.leaplaughlove.marketdata.instrument.SimulatedInstrument;
import com.leap.leaplaughlove.marketdata.instrument.SimulatedInstrumentRepository;
import com.leap.leaplaughlove.marketdata.simulation.PriceState;
import com.leap.leaplaughlove.marketdata.simulation.PriceTickEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("PriceCandleAccumulator Unit Tests")
class PriceCandleAccumulatorTest {

    @Mock
    private SimulatedInstrumentRepository instrumentRepository;

    @Mock
    private PriceCandleRepository candleRepository;

    private PriceCandleAccumulator accumulator;

    /** A minute boundary that is also an hour boundary, so both widths roll over together. */
    private static final long HOUR_BOUNDARY = 1_700_000_000L - (1_700_000_000L % 3600);

    @BeforeEach
    void setUp() {
        SimulatedInstrument aapl = new SimulatedInstrument(
                UUID.randomUUID(), "AAPL", "Apple Inc.",
                new BigDecimal("150.00"), new BigDecimal("0.07"), new BigDecimal("0.25"), 42L, true);
        when(instrumentRepository.findByActiveTrue()).thenReturn(List.of(aapl));

        accumulator = new PriceCandleAccumulator(instrumentRepository, candleRepository, List.of(60, 3600));
        accumulator.initialize();
    }

    private void tick(long epochSeconds, String price) {
        accumulator.onPriceTick(new PriceTickEvent(new PriceState(
                "AAPL",
                new BigDecimal(price),
                OffsetDateTime.ofInstant(Instant.ofEpochSecond(epochSeconds), ZoneOffset.UTC))));
    }

    @SuppressWarnings("unchecked")
    private List<PriceCandle> flushAndCapture() {
        accumulator.flushStaleBuckets();
        ArgumentCaptor<List<PriceCandle>> captor = ArgumentCaptor.forClass(List.class);
        verify(candleRepository).saveAll(captor.capture());
        return captor.getValue();
    }

    private static PriceCandle candleAt(List<PriceCandle> candles, int width, long bucketStart) {
        return candles.stream()
                .filter(c -> c.getBucketSeconds() == width)
                .filter(c -> c.getBucketStart().toEpochSecond() == bucketStart)
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "no " + width + "s candle at " + bucketStart + " in " + candles.size() + " candles"));
    }

    @Test
    @DisplayName("a tick is folded into every configured bucket width")
    void testTickFoldsIntoEveryWidth() {
        tick(HOUR_BOUNDARY, "150.00");
        tick(HOUR_BOUNDARY + 10, "152.00");
        tick(HOUR_BOUNDARY + 20, "149.00");
        // Crossing into the next minute rolls the 60s bucket but not the hourly one.
        tick(HOUR_BOUNDARY + 60, "151.00");

        List<PriceCandle> written = flushAndCapture();

        PriceCandle minute = candleAt(written, 60, HOUR_BOUNDARY);
        assertEquals(0, new BigDecimal("150.00").compareTo(minute.getOpen()));
        assertEquals(0, new BigDecimal("152.00").compareTo(minute.getHigh()));
        assertEquals(0, new BigDecimal("149.00").compareTo(minute.getLow()));
        assertEquals(0, new BigDecimal("149.00").compareTo(minute.getClose()));

        PriceCandle hour = candleAt(written, 3600, HOUR_BOUNDARY);
        assertEquals(0, new BigDecimal("152.00").compareTo(hour.getHigh()),
                "the hourly candle should see the same ticks as the minute candles inside it");
    }

    @Test
    @DisplayName("each width rolls over on its own boundary")
    void testWidthsRollOverIndependently() {
        tick(HOUR_BOUNDARY, "150.00");
        tick(HOUR_BOUNDARY + 1800, "160.00");
        // One hour on: the hourly bucket has elapsed too.
        tick(HOUR_BOUNDARY + 3600, "155.00");

        List<PriceCandle> written = flushAndCapture();

        PriceCandle firstHour = candleAt(written, 3600, HOUR_BOUNDARY);
        assertEquals(0, new BigDecimal("150.00").compareTo(firstHour.getOpen()));
        assertEquals(0, new BigDecimal("160.00").compareTo(firstHour.getHigh()));
        assertEquals(0, new BigDecimal("150.00").compareTo(firstHour.getLow()));
        assertEquals(0, new BigDecimal("160.00").compareTo(firstHour.getClose()),
                "the hourly candle closes on the last tick inside the hour");

        PriceCandle secondHour = candleAt(written, 3600, HOUR_BOUNDARY + 3600);
        assertEquals(0, new BigDecimal("155.00").compareTo(secondHour.getOpen()));
    }

    @Test
    @DisplayName("ticks alone never touch the database")
    void testTicksDoNotWriteInline() {
        tick(HOUR_BOUNDARY, "150.00");
        tick(HOUR_BOUNDARY + 30, "151.00");
        // Enough ticks to roll the minute bucket several times over.
        tick(HOUR_BOUNDARY + 60, "152.00");
        tick(HOUR_BOUNDARY + 120, "153.00");
        tick(HOUR_BOUNDARY + 180, "154.00");

        // The simulation publishes events synchronously on its tick thread, so a write here
        // would be a database round trip inside the tick interval, per instrument.
        verify(candleRepository, never()).save(any());
        verify(candleRepository, never()).saveAll(any());
    }

    @Test
    @DisplayName("a flush writes every queued candle in one batch")
    void testFlushWritesOneBatch() {
        tick(HOUR_BOUNDARY, "150.00");
        tick(HOUR_BOUNDARY + 60, "151.00");
        tick(HOUR_BOUNDARY + 120, "152.00");
        tick(HOUR_BOUNDARY + 180, "153.00");

        List<PriceCandle> written = flushAndCapture();

        assertTrue(written.size() >= 4, "expected the rolled-over candles in the batch");
        // One saveAll, never a per-candle save: batching is the whole point of the queue.
        verify(candleRepository, never()).save(any());
    }

    @Test
    @DisplayName("a flush with nothing queued does not write")
    void testEmptyFlushDoesNotWrite() {
        accumulator.flushStaleBuckets();

        verify(candleRepository, never()).saveAll(any());
        verify(candleRepository, never()).save(any());
    }

    @Test
    @DisplayName("a bucket width of zero or less is rejected")
    void testRejectsNonPositiveWidths() {
        assertThrows(IllegalArgumentException.class, () ->
                new PriceCandleAccumulator(instrumentRepository, candleRepository, List.of(60, 0)));
        assertThrows(IllegalArgumentException.class, () ->
                new PriceCandleAccumulator(instrumentRepository, candleRepository, List.of()));
    }
}
