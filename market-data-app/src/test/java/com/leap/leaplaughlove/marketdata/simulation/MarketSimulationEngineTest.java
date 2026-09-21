package com.leap.leaplaughlove.marketdata.simulation;

import com.leap.leaplaughlove.marketdata.history.PriceCandle;
import com.leap.leaplaughlove.marketdata.history.PriceCandleRepository;
import com.leap.leaplaughlove.marketdata.instrument.SimulatedInstrument;
import com.leap.leaplaughlove.marketdata.instrument.SimulatedInstrumentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("MarketSimulationEngine Unit Tests")
class MarketSimulationEngineTest {

    @Mock
    private SimulatedInstrumentRepository instrumentRepository;

    @Mock
    private PriceCandleRepository candleRepository;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    private MarketSimulationEngine engine;

    @BeforeEach
    void setUp() {
        SimulatedInstrument aapl = new SimulatedInstrument(
                UUID.randomUUID(), "AAPL", "Apple Inc.",
                new BigDecimal("150.00"), new BigDecimal("0.07"), new BigDecimal("0.25"), 42L, true);
        when(instrumentRepository.findByActiveTrue()).thenReturn(List.of(aapl));

        engine = new MarketSimulationEngine(instrumentRepository, candleRepository, eventPublisher, 1000L);
        engine.initialize();
    }

    @Test
    @DisplayName("initialize seeds the latest price from each active instrument's initial price")
    void testInitializeSeedsLatestPrice() {
        Optional<PriceState> state = engine.latest("AAPL");

        assertTrue(state.isPresent());
        assertEquals(0, new BigDecimal("150.000000").compareTo(state.get().price()));
    }

    @Test
    @DisplayName("initialize resumes from the newest persisted candle close instead of the seed price")
    void testInitializeResumesFromLatestCandleClose() {
        PriceCandle latest = mock(PriceCandle.class);
        when(latest.getClose()).thenReturn(new BigDecimal("182.520000"));
        when(candleRepository.findFirstByInstrument_SymbolOrderByBucketStartDescBucketSecondsAsc("AAPL"))
                .thenReturn(Optional.of(latest));

        engine.initialize();

        BigDecimal price = engine.latest("AAPL").orElseThrow().price();
        assertEquals(0, new BigDecimal("182.520000").compareTo(price),
                "a restart must continue from the last persisted price, not jump back to the seed price");
    }

    @Test
    @DisplayName("tick advances the latest price and publishes a PriceTickEvent")
    void testTickAdvancesPriceAndPublishesEvent() {
        BigDecimal before = engine.latest("AAPL").orElseThrow().price();

        engine.tick();

        BigDecimal after = engine.latest("AAPL").orElseThrow().price();
        assertNotEquals(0, before.compareTo(after), "price should change after a tick");

        ArgumentCaptor<PriceTickEvent> captor = ArgumentCaptor.forClass(PriceTickEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertEquals("AAPL", captor.getValue().priceState().symbol());
        assertEquals(0, after.compareTo(captor.getValue().priceState().price()));
    }

    @Test
    @DisplayName("latest returns empty for an unknown symbol")
    void testLatestUnknownSymbol() {
        assertTrue(engine.latest("DOES_NOT_EXIST").isEmpty());
    }

    @Test
    @DisplayName("latestAll returns one entry per active instrument")
    void testLatestAllReturnsAllInstruments() {
        assertEquals(1, engine.latestAll().size());
    }
}
