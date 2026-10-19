package com.leap.leaplaughlove.marketdata.simulation;

import com.leap.leaplaughlove.common.market.MarketClock;
import com.leap.leaplaughlove.common.market.MarketSession;
import com.leap.leaplaughlove.marketdata.history.PriceCandle;
import com.leap.leaplaughlove.marketdata.history.PriceCandleRepository;
import com.leap.leaplaughlove.marketdata.instrument.SimulatedInstrument;
import com.leap.leaplaughlove.marketdata.instrument.SimulatedInstrumentRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.random.RandomGenerator;

/**
 * Simulates instrument prices using Geometric Brownian Motion, ticking every
 * active
 * instrument on a fixed schedule and publishing a {@link PriceTickEvent} per
 * tick.
 *
 * <p>
 * Prices follow the market session ({@link MarketClock}):
 * <ul>
 * <li>REGULAR: full volatility.</li>
 * <li>PRE_MARKET and AFTER_HOURS: thin trading, so volatility is scaled down by
 * {@code extended-vol-factor}.</li>
 * <li>CLOSED: the price is frozen. Ticks are still published at that price, so
 * quotes stay fresh and the app keeps showing the last price.</li>
 * </ul>
 * When trading resumes after a closed stretch, each price first jumps once - the
 * opening gap - by the move GBM would have made over the time it was closed,
 * capped at {@code gap-cap-pct}. It is drawn from the instrument's own seeded
 * random generator, like every other step.
 */
@Service
public class MarketSimulationEngine {

    /**
     * Order of {@link #initialize()} among {@code ApplicationReadyEvent} listeners:
     * last. Seeding
     * the state is what starts ticks flowing (the scheduled {@link #tick()} is
     * already running),
     * so every tick consumer that loads a lookup on the same event must run before
     * it - order
     * those with {@link #TICK_CONSUMER_ORDER}.
     */
    public static final int START_ORDER = Ordered.LOWEST_PRECEDENCE;

    /**
     * Order for tick consumers' {@code ApplicationReadyEvent} setup: before
     * {@link #START_ORDER}.
     */
    public static final int TICK_CONSUMER_ORDER = START_ORDER - 1;

    private static final int PRICE_SCALE = 6;
    private static final double SECONDS_PER_YEAR = 365.0 * 24 * 60 * 60;

    private final SimulatedInstrumentRepository instrumentRepository;
    private final PriceCandleRepository candleRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final long tickIntervalMs;
    private final List<Integer> candleBucketSeconds;
    private final MarketClock marketClock;
    private final double extendedVolFactor;
    private final double gapCapFraction;

    /** When the market last closed, or null while it is trading. */
    private Instant closedSince;

    private final Map<String, InstrumentSimState> statesBySymbol = new ConcurrentHashMap<>();

    /**
     * Creates a new MarketSimulationEngine with the given collaborators and tick
     * interval.
     * 
     * @param instrumentRepository repository used to look up active instruments at
     *                             startup
     * @param candleRepository     repository used to resume each instrument's last
     *                             persisted price
     * @param eventPublisher       publisher used to broadcast
     *                             {@link PriceTickEvent}s
     * @param tickIntervalMs       the interval, in milliseconds, between simulation
     *                             ticks
     * @param candleBucketSeconds  the candle widths, in seconds, searched for the
     *                             price to resume from
     */
    public MarketSimulationEngine(SimulatedInstrumentRepository instrumentRepository,
            PriceCandleRepository candleRepository,
            ApplicationEventPublisher eventPublisher,
            long tickIntervalMs,
            List<Integer> candleBucketSeconds) {
        this(instrumentRepository, candleRepository, eventPublisher, tickIntervalMs, candleBucketSeconds,
                new MarketClock(Clock.systemUTC(), MarketClock.Mode.ALWAYS_OPEN, null, null), 1.0, 0.0);
    }

    /**
     * Creates a new MarketSimulationEngine whose prices follow the market session.
     *
     * @param instrumentRepository repository used to look up active instruments at
     *                             startup
     * @param candleRepository     repository used to resume each instrument's last
     *                             persisted price
     * @param eventPublisher       publisher used to broadcast
     *                             {@link PriceTickEvent}s
     * @param tickIntervalMs       the interval, in milliseconds, between simulation
     *                             ticks
     * @param candleBucketSeconds  the candle widths, in seconds, searched for the
     *                             price to resume from
     * @param marketClock          decides the session each tick is made in
     * @param extendedVolFactor    volatility multiplier in pre-market and
     *                             after-hours, e.g. 0.4
     * @param gapCapPct            the largest opening gap, in percent; 0 for none
     */
    @Autowired
    public MarketSimulationEngine(SimulatedInstrumentRepository instrumentRepository,
            PriceCandleRepository candleRepository,
            ApplicationEventPublisher eventPublisher,
            @Value("${marketdata.simulation.tick-interval-ms:1000}") long tickIntervalMs,
            @Value("${marketdata.simulation.candle-bucket-seconds:60,300,3600,86400}") List<Integer> candleBucketSeconds,
            MarketClock marketClock,
            @Value("${marketdata.simulation.extended-vol-factor:0.4}") double extendedVolFactor,
            @Value("${marketdata.simulation.gap-cap-pct:3}") double gapCapPct) {
        if (extendedVolFactor < 0) {
            throw new IllegalArgumentException("extended-vol-factor must not be negative");
        }
        if (gapCapPct < 0) {
            throw new IllegalArgumentException("gap-cap-pct must not be negative");
        }
        this.instrumentRepository = instrumentRepository;
        this.candleRepository = candleRepository;
        this.eventPublisher = eventPublisher;
        this.tickIntervalMs = tickIntervalMs;
        this.candleBucketSeconds = List.copyOf(candleBucketSeconds);
        this.marketClock = marketClock;
        this.extendedVolFactor = extendedVolFactor;
        this.gapCapFraction = gapCapPct / 100.0;
    }

    /**
     * Seeds the simulation state for every active instrument once the application
     * context is
     * ready, resuming from the newest persisted candle close where one exists and
     * falling back
     * to the instrument's configured initial price otherwise.
     *
     * <p>
     * Resuming matters because this runs on every boot. Restarting each instrument
     * at its
     * seed price made the persisted series jump back to that price at every
     * restart, so a chart
     * drawn over the candle history showed a sawtooth no market movement produced.
     * It is also
     * what lets the historical backfill hand off continuously: the backfill's final
     * close
     * becomes the live feed's opening price.
     */
    @EventListener(ApplicationReadyEvent.class)
    @Order(START_ORDER)
    public void initialize() {
        for (SimulatedInstrument instrument : instrumentRepository.findByActiveTrue()) {
            RandomGenerator random = new Random(instrument.getRngSeed());
            BigDecimal startingPrice = candleRepository
                    .findNewest(instrument.getSymbol(), candleBucketSeconds)
                    .map(PriceCandle::getClose)
                    .orElseGet(instrument::getInitialPrice);
            PriceState initialState = new PriceState(
                    instrument.getSymbol(),
                    startingPrice.setScale(PRICE_SCALE, RoundingMode.HALF_UP),
                    OffsetDateTime.now(),
                    marketClock.session());
            statesBySymbol.put(instrument.getSymbol(), new InstrumentSimState(
                    instrument.getDisplayName(),
                    instrument.getDrift().doubleValue(),
                    instrument.getVolatility().doubleValue(),
                    random,
                    initialState));
        }
    }

    /**
     * Advances every active instrument's simulated price by one GBM step and
     * publishes a
     * {@link PriceTickEvent} for each.
     */
    @Scheduled(fixedRateString = "${marketdata.simulation.tick-interval-ms:1000}")
    public synchronized void tick() {
        Instant now = marketClock.now();
        MarketSession session = marketClock.sessionAt(now);
        double gapYears = closedYearsEndingAt(now, session);

        double dt = tickIntervalMs / 1000.0 / SECONDS_PER_YEAR;
        double volatilityFactor = session.isExtendedHours() ? extendedVolFactor : 1.0;
        OffsetDateTime asOf = OffsetDateTime.now();
        statesBySymbol.forEach((symbol, state) -> {
            double price = state.current().price().doubleValue();
            if (session != MarketSession.CLOSED) {
                if (gapYears > 0 && gapCapFraction > 0) {
                    price = openingGap(price, state, gapYears);
                }
                double volatility = state.volatility() * volatilityFactor;
                if (volatility > 0) {
                    price = GbmPriceGenerator.nextPrice(price, state.drift(), volatility, dt, state.random());
                }
            }
            PriceState next = new PriceState(
                    symbol,
                    BigDecimal.valueOf(price).setScale(PRICE_SCALE, RoundingMode.HALF_UP),
                    asOf,
                    session);
            state.update(next);
            eventPublisher.publishEvent(new PriceTickEvent(next));
        });
    }

    /**
     * Tracks closed stretches across ticks.
     *
     * @param now     this tick's time
     * @param session this tick's session
     * @return how long, in years, the market was closed if this tick reopens it;
     *         0 otherwise
     */
    private double closedYearsEndingAt(Instant now, MarketSession session) {
        double closedYears = 0;
        if (session == MarketSession.CLOSED) {
            if (closedSince == null) {
                closedSince = now;
            }
        } else {
            if (closedSince != null) {
                closedYears = Duration.between(closedSince, now).toMillis() / 1000.0 / SECONDS_PER_YEAR;
            }
            closedSince = null;
        }
        return closedYears;
    }

    /**
     * The price after the opening gap: the GBM move over the closed stretch,
     * capped at {@code gap-cap-pct} either way.
     *
     * @param price       the price the market closed at
     * @param state       the instrument's simulation state
     * @param closedYears how long the market was closed, in years
     * @return the price trading reopens from
     */
    private double openingGap(double price, InstrumentSimState state, double closedYears) {
        double gapped = GbmPriceGenerator.nextPrice(price, state.drift(), state.volatility(), closedYears,
                state.random());
        return Math.clamp(gapped, price * (1 - gapCapFraction), price * (1 + gapCapFraction));
    }


    /**
     * Gets the latest simulated price for a single instrument symbol.
     * 
     * @param symbol the instrument symbol to look up
     * @return the latest price state for the symbol, if it is being simulated
     */
    public Optional<PriceState> latest(String symbol) {
        return Optional.ofNullable(statesBySymbol.get(symbol)).map(InstrumentSimState::current);
    }

    /**
     * Gets the display name of an actively-simulated instrument, e.g. "Apple Inc."
     * for AAPL.
     * 
     * @param symbol the instrument symbol to look up
     * @return the instrument's display name, if it is being simulated
     */
    public Optional<String> displayName(String symbol) {
        return Optional.ofNullable(statesBySymbol.get(symbol)).map(InstrumentSimState::displayName);
    }

    /**
     * Gets the latest simulated price for every actively-simulated instrument.
     * 
     * @return the latest price state for each simulated instrument
     */
    public List<PriceState> latestAll() {
        return statesBySymbol.values().stream().map(InstrumentSimState::current).toList();
    }

    /**
     * Per-instrument simulation state: its display name, GBM parameters, random
     * generator, and
     * the most recently simulated price.
     */
    private static final class InstrumentSimState {
        private final String displayName;
        private final double drift;
        private final double volatility;
        private final RandomGenerator random;
        private volatile PriceState current;

        /**
         * Creates a new InstrumentSimState.
         * 
         * @param displayName the display name of the instrument
         * @param drift       the drift used by the GBM simulation
         * @param volatility  the volatility used by the GBM simulation
         * @param random      the random generator used by the GBM simulation
         * @param current     the current price state
         */
        private InstrumentSimState(String displayName, double drift, double volatility,
                RandomGenerator random, PriceState current) {
            this.displayName = displayName;
            this.drift = drift;
            this.volatility = volatility;
            this.random = random;
            this.current = current;
        }

        /**
         * Gets the display name of the instrument.
         * 
         * @return the display name of the instrument
         */
        String displayName() {
            return displayName;
        }

        /**
         * Gets the drift used by the GBM simulation.
         * 
         * @return the drift used by the GBM simulation
         */
        double drift() {
            return drift;
        }

        /**
         * Gets the volatility used by the GBM simulation.
         * 
         * @return the volatility used by the GBM simulation
         */
        double volatility() {
            return volatility;
        }

        /**
         * Gets the random generator used by the GBM simulation.
         * 
         * @return the random generator used by the GBM simulation
         */
        RandomGenerator random() {
            return random;
        }

        /**
         * Gets the current price state.
         * 
         * @return the current price state
         */
        PriceState current() {
            return current;
        }

        /**
         * Updates the current price state.
         * 
         * @param next the next price state
         */
        void update(PriceState next) {
            this.current = next;
        }
    }
}
