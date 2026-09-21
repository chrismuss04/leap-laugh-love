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
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

    @Test
    @DisplayName("a tick is folded into every configured bucket width")
    void testTickFoldsIntoEveryWidth() {
        tick(HOUR_BOUNDARY, "150.00");
        tick(HOUR_BOUNDARY + 10, "152.00");
        tick(HOUR_BOUNDARY + 20, "149.00");
        // Crossing into the next minute rolls the 60s bucket but not the hourly one.
        tick(HOUR_BOUNDARY + 60, "151.00");

        ArgumentCaptor<PriceCandle> captor = ArgumentCaptor.forClass(PriceCandle.class);
        verify(candleRepository).save(captor.capture());

        PriceCandle minute = captor.getValue();
        assertEquals(60, minute.getBucketSeconds());
        assertEquals(0, new BigDecimal("150.00").compareTo(minute.getOpen()));
        assertEquals(0, new BigDecimal("152.00").compareTo(minute.getHigh()));
        assertEquals(0, new BigDecimal("149.00").compareTo(minute.getLow()));
        assertEquals(0, new BigDecimal("149.00").compareTo(minute.getClose()));
    }

    @Test
    @DisplayName("each width rolls over on its own boundary")
    void testWidthsRollOverIndependently() {
        tick(HOUR_BOUNDARY, "150.00");
        tick(HOUR_BOUNDARY + 1800, "160.00");
        // One hour on: both the minute bucket and the hour bucket have elapsed.
        tick(HOUR_BOUNDARY + 3600, "155.00");

        ArgumentCaptor<PriceCandle> captor = ArgumentCaptor.forClass(PriceCandle.class);
        verify(candleRepository, org.mockito.Mockito.atLeast(2)).save(captor.capture());

        Map<Integer, List<PriceCandle>> byWidth = captor.getAllValues().stream()
                .collect(Collectors.groupingBy(PriceCandle::getBucketSeconds));

        assertEquals(2, byWidth.size(), "both widths should have flushed a completed bucket");

        PriceCandle hour = byWidth.get(3600).get(byWidth.get(3600).size() - 1);
        assertEquals(0, new BigDecimal("150.00").compareTo(hour.getOpen()));
        assertEquals(0, new BigDecimal("160.00").compareTo(hour.getHigh()));
        assertEquals(0, new BigDecimal("160.00").compareTo(hour.getClose()),
                "the hourly candle closes on the last tick inside the hour");
        assertEquals(HOUR_BOUNDARY, hour.getBucketStart().toEpochSecond());
    }

    @Test
    @DisplayName("an in-progress bucket is not persisted")
    void testInProgressBucketIsNotPersisted() {
        tick(HOUR_BOUNDARY, "150.00");
        tick(HOUR_BOUNDARY + 30, "151.00");

        verify(candleRepository, never()).save(org.mockito.ArgumentMatchers.any());
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
