package com.leap.leaplaughlove.marketdata.simulation;

import com.leap.leaplaughlove.marketdata.instrument.SimulatedInstrument;
import com.leap.leaplaughlove.marketdata.instrument.SimulatedInstrumentRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.random.RandomGenerator;

@Service
public class MarketSimulationEngine {

    private static final int PRICE_SCALE = 6;
    private static final double SECONDS_PER_YEAR = 365.0 * 24 * 60 * 60;

    private final SimulatedInstrumentRepository instrumentRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final long tickIntervalMs;

    private final Map<String, InstrumentSimState> statesBySymbol = new ConcurrentHashMap<>();

    public MarketSimulationEngine(SimulatedInstrumentRepository instrumentRepository,
                                   ApplicationEventPublisher eventPublisher,
                                   @Value("${marketdata.simulation.tick-interval-ms:1000}") long tickIntervalMs) {
        this.instrumentRepository = instrumentRepository;
        this.eventPublisher = eventPublisher;
        this.tickIntervalMs = tickIntervalMs;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void initialize() {
        for (SimulatedInstrument instrument : instrumentRepository.findByActiveTrue()) {
            RandomGenerator random = new Random(instrument.getRngSeed());
            PriceState initialState = new PriceState(
                    instrument.getSymbol(),
                    instrument.getInitialPrice().setScale(PRICE_SCALE, RoundingMode.HALF_UP),
                    OffsetDateTime.now());
            statesBySymbol.put(instrument.getSymbol(), new InstrumentSimState(
                    instrument.getDrift().doubleValue(),
                    instrument.getVolatility().doubleValue(),
                    random,
                    initialState));
        }
    }

    @Scheduled(fixedRateString = "${marketdata.simulation.tick-interval-ms:1000}")
    public void tick() {
        double dt = tickIntervalMs / 1000.0 / SECONDS_PER_YEAR;
        statesBySymbol.forEach((symbol, state) -> {
            double nextPrice = GbmPriceGenerator.nextPrice(
                    state.current().price().doubleValue(), state.drift(), state.volatility(), dt, state.random());
            PriceState next = new PriceState(
                    symbol,
                    BigDecimal.valueOf(nextPrice).setScale(PRICE_SCALE, RoundingMode.HALF_UP),
                    OffsetDateTime.now());
            state.update(next);
            eventPublisher.publishEvent(new PriceTickEvent(next));
        });
    }

    public Optional<PriceState> latest(String symbol) {
        return Optional.ofNullable(statesBySymbol.get(symbol)).map(InstrumentSimState::current);
    }

    public List<PriceState> latestAll() {
        return statesBySymbol.values().stream().map(InstrumentSimState::current).toList();
    }

    private static final class InstrumentSimState {
        private final double drift;
        private final double volatility;
        private final RandomGenerator random;
        private volatile PriceState current;

        private InstrumentSimState(double drift, double volatility, RandomGenerator random, PriceState current) {
            this.drift = drift;
            this.volatility = volatility;
            this.random = random;
            this.current = current;
        }

        double drift() { return drift; }
        double volatility() { return volatility; }
        RandomGenerator random() { return random; }
        PriceState current() { return current; }
        void update(PriceState next) { this.current = next; }
    }
}
