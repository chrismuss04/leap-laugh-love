package com.leap.leaplaughlove.marketdata.api;

import com.leap.leaplaughlove.marketdata.history.PriceCandle;
import com.leap.leaplaughlove.marketdata.history.PriceCandleRepository;
import com.leap.leaplaughlove.marketdata.instrument.SimulatedInstrument;
import com.leap.leaplaughlove.marketdata.simulation.MarketSimulationEngine;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@DisplayName("PriceController history validation and mapping")
class PriceControllerUnitTest {

    private static final OffsetDateTime TO = OffsetDateTime.parse("2026-01-02T00:00:00Z");

    private final MarketSimulationEngine engine = mock(MarketSimulationEngine.class);
    private final PriceCandleRepository candles = mock(PriceCandleRepository.class);
    private final PriceController controller = new PriceController(engine, candles);

    private HttpStatus statusOf(Runnable call) {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class, call::run);
        return HttpStatus.valueOf(ex.getStatusCode().value());
    }

    @Test
    @DisplayName("refuses a negative page")
    void rejectsNegativePage() {
        assertEquals(HttpStatus.BAD_REQUEST, statusOf(() -> controller.getHistory("AAPL", null, null, 60, -1, 10)));
        verifyNoInteractions(candles);
    }

    @Test
    @DisplayName("refuses a page size outside 1 to 1000")
    void rejectsBadSize() {
        assertEquals(HttpStatus.BAD_REQUEST, statusOf(() -> controller.getHistory("AAPL", null, null, 60, 0, 0)));
        assertEquals(HttpStatus.BAD_REQUEST, statusOf(() -> controller.getHistory("AAPL", null, null, 60, 0, 1001)));
        verifyNoInteractions(candles);
    }

    @Test
    @DisplayName("refuses a range that starts after it ends")
    void rejectsInvertedRange() {
        assertEquals(HttpStatus.BAD_REQUEST,
                statusOf(() -> controller.getHistory("AAPL", TO.plusDays(1), TO, 60, 0, 10)));
        verifyNoInteractions(candles);
    }

    @Test
    @DisplayName("maps stored candles to API responses, newest first as stored")
    void mapsCandles() {
        SimulatedInstrument instrument = new SimulatedInstrument(UUID.randomUUID(), "AAPL", "Apple Inc.",
                new BigDecimal("100"), new BigDecimal("0.05"), new BigDecimal("0.2"), 1L, true);
        PriceCandle candle = new PriceCandle(instrument, TO.minusMinutes(5), 300, new BigDecimal("1"),
                new BigDecimal("4"), new BigDecimal("0.5"), new BigDecimal("3"));
        when(candles.findByInstrument_SymbolAndBucketSecondsAndBucketStartBetweenOrderByBucketStartDesc(
                eq("AAPL"), eq(300), any(OffsetDateTime.class), eq(TO), eq(PageRequest.of(0, 10))))
                .thenReturn(new PageImpl<>(List.of(candle)));

        Page<PriceCandleResponse> page = controller.getHistory("aapl", TO.minusDays(1), TO, 300, 0, 10);

        assertEquals(1, page.getTotalElements());
        PriceCandleResponse response = page.getContent().get(0);
        assertEquals(TO.minusMinutes(5), response.bucketStart());
        assertEquals(300, response.bucketSeconds());
        assertEquals(new BigDecimal("1"), response.open());
        assertEquals(new BigDecimal("4"), response.high());
        assertEquals(new BigDecimal("0.5"), response.low());
        assertEquals(new BigDecimal("3"), response.close());
    }
}
