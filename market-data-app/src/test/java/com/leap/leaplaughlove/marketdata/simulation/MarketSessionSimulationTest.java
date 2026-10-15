package com.leap.leaplaughlove.marketdata.simulation;

import com.leap.leaplaughlove.common.market.MarketClock;
import com.leap.leaplaughlove.common.market.MarketSession;
import com.leap.leaplaughlove.marketdata.history.PriceCandleRepository;
import com.leap.leaplaughlove.marketdata.instrument.SimulatedInstrument;
import com.leap.leaplaughlove.marketdata.instrument.SimulatedInstrumentRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * How simulated prices follow the market session. Sessions are pinned with a forced
 * {@link MarketClock}, and a forced session that runs out opens the market, so a test can close
 * and reopen it by moving the clock.
 */
@DisplayName("MarketSimulationEngine market sessions")
class MarketSessionSimulationTest {

    private static final List<Integer> WIDTHS = List.of(60, 300, 3600, 86400);
    private static final Instant START = Instant.parse("2026-10-17T16:00:00Z");

    /** A clock a test can move. */
    private static final class MovableClock extends Clock {
        private final AtomicReference<Instant> now = new AtomicReference<>(START);

        void advance(Duration by) {
            now.updateAndGet(instant -> instant.plus(by));
        }

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now.get(); }
    }

    private final ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);

    private MarketSimulationEngine engine(MarketClock clock, double extendedVolFactor, double gapCapPct) {
        SimulatedInstrumentRepository instruments = mock(SimulatedInstrumentRepository.class);
        when(instruments.findByActiveTrue()).thenReturn(List.of(new SimulatedInstrument(
                UUID.randomUUID(), "AAPL", "Apple Inc.",
                new BigDecimal("100.00"), new BigDecimal("0.07"), new BigDecimal("0.25"), 42L, true)));
        MarketSimulationEngine engine = new MarketSimulationEngine(instruments, mock(PriceCandleRepository.class),
                publisher, 1000L, WIDTHS, clock, extendedVolFactor, gapCapPct);
        engine.initialize();
        return engine;
    }

    private static MarketClock pinned(Clock clock, MarketSession session, Duration until) {
        return new MarketClock(clock, MarketClock.Mode.REAL, session, until);
    }

    private static double price(MarketSimulationEngine engine) {
        return engine.latest("AAPL").orElseThrow().price().doubleValue();
    }

    @Test
    @DisplayName("while closed the price is frozen, and ticks still go out marked CLOSED")
    void closedFreezesThePrice() {
        MarketSimulationEngine engine = engine(pinned(new MovableClock(), MarketSession.CLOSED, Duration.ZERO), 0.4, 3);

        for (int i = 0; i < 5; i++) {
            engine.tick();
        }

        assertEquals(100.0, price(engine));
        ArgumentCaptor<PriceTickEvent> ticks = ArgumentCaptor.forClass(PriceTickEvent.class);
        verify(publisher, atLeastOnce()).publishEvent(ticks.capture());
        assertEquals(5, ticks.getAllValues().size());
        assertTrue(ticks.getAllValues().stream().allMatch(t -> t.priceState().session() == MarketSession.CLOSED));
    }

    @Test
    @DisplayName("the starting price carries the session in force")
    void startingPriceCarriesTheSession() {
        MarketSimulationEngine engine = engine(pinned(new MovableClock(), MarketSession.AFTER_HOURS, Duration.ZERO), 0.4, 3);

        assertEquals(MarketSession.AFTER_HOURS, engine.latest("AAPL").orElseThrow().session());
    }

    @Test
    @DisplayName("reopening after a long close gaps the price once, by no more than the cap")
    void reopeningGapsOnceWithinTheCap() {
        MovableClock time = new MovableClock();
        // Closed for a year, then open: the uncapped move would almost surely exceed 3%.
        MarketSimulationEngine engine = engine(pinned(time, MarketSession.CLOSED, Duration.ofDays(365)), 0.4, 3);
        engine.tick();
        time.advance(Duration.ofDays(365));

        engine.tick();
        double afterGap = price(engine);
        engine.tick();
        double nextTick = price(engine);

        double gap = Math.abs(afterGap / 100.0 - 1);
        assertTrue(gap > 0.029 && gap < 0.031, "gap should be capped at 3%, was " + gap);
        assertTrue(Math.abs(nextTick / afterGap - 1) < 0.001, "the gap only happens once");
        assertEquals(MarketSession.REGULAR, engine.latest("AAPL").orElseThrow().session());
    }

    @Test
    @DisplayName("a gap cap of zero turns the opening gap off")
    void zeroCapMeansNoGap() {
        MovableClock time = new MovableClock();
        MarketSimulationEngine engine = engine(pinned(time, MarketSession.CLOSED, Duration.ofDays(365)), 0.4, 0);
        engine.tick();
        time.advance(Duration.ofDays(365));

        engine.tick();

        assertTrue(Math.abs(price(engine) / 100.0 - 1) < 0.001, "only an ordinary one-second move");
    }

    @Test
    @DisplayName("pre-market and after-hours move at the reduced volatility")
    void extendedHoursAreCalmer() {
        // Same seed, so the same random draw: only the volatility differs.
        MarketSimulationEngine regular = engine(pinned(new MovableClock(), MarketSession.REGULAR, Duration.ZERO), 0.4, 3);
        MarketSimulationEngine extended = engine(pinned(new MovableClock(), MarketSession.PRE_MARKET, Duration.ZERO), 0.4, 3);

        regular.tick();
        extended.tick();

        double regularMove = Math.log(price(regular) / 100.0);
        double extendedMove = Math.log(price(extended) / 100.0);
        assertEquals(0.4, extendedMove / regularMove, 0.01);
    }

    @Test
    @DisplayName("an extended-hours volatility factor of zero holds the price still")
    void zeroExtendedVolatility() {
        MarketSimulationEngine engine = engine(pinned(new MovableClock(), MarketSession.AFTER_HOURS, Duration.ZERO), 0, 3);

        engine.tick();

        assertEquals(100.0, price(engine));
    }

    @Test
    @DisplayName("rejects negative settings")
    void rejectsNegativeSettings() {
        MarketClock clock = pinned(new MovableClock(), MarketSession.REGULAR, Duration.ZERO);
        assertThrows(IllegalArgumentException.class, () -> new MarketSimulationEngine(
                mock(SimulatedInstrumentRepository.class), mock(PriceCandleRepository.class), publisher,
                1000L, WIDTHS, clock, -0.1, 3));
        assertThrows(IllegalArgumentException.class, () -> new MarketSimulationEngine(
                mock(SimulatedInstrumentRepository.class), mock(PriceCandleRepository.class), publisher,
                1000L, WIDTHS, clock, 0.4, -1));
    }
}
