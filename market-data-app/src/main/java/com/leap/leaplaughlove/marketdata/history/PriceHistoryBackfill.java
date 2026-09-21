package com.leap.leaplaughlove.marketdata.history;

import com.leap.leaplaughlove.marketdata.instrument.SimulatedInstrument;
import com.leap.leaplaughlove.marketdata.instrument.SimulatedInstrumentRepository;
import com.leap.leaplaughlove.marketdata.simulation.GbmPriceGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.random.RandomGenerator;

/**
 * Generates a synthetic price history for instruments that have none, so charts have something
 * to draw before the live simulation has been running long enough to produce it. Without this,
 * candle history only covers the current process's uptime and every range longer than that is
 * empty.
 *
 * <p>One GBM path is walked per instrument at {@code stepSeconds} resolution across the whole
 * lookback, and every configured bucket width is folded out of that single path. Deriving all
 * widths from one walk is what keeps them consistent: the daily, hourly and minute series are
 * aggregations of the same prices rather than independent random paths that would disagree
 * about what the price was on a given afternoon. Only the finest width is stored at full
 * resolution over a short window; wider widths cover proportionally longer ones, which is what
 * makes a year of history cost thousands of rows per instrument instead of ~525,000.
 *
 * <p>Runs as an {@link ApplicationRunner}, which Spring Boot invokes before it publishes
 * {@code ApplicationReadyEvent}. That ordering is deliberate and load-bearing: the backfill
 * finishes before {@code MarketSimulationEngine.initialize()} reads the latest close, so the
 * live feed resumes from the end of the generated history instead of jumping back to the seed
 * price.
 *
 * <p>Idempotent per instrument - an instrument that already has any candle is left untouched,
 * so this is a no-op on every boot after the first. Each instrument is written in its own
 * transaction, so a failure part-way through cannot leave an instrument holding a truncated
 * history that the idempotency check would then treat as complete.
 */
@Component
@ConditionalOnProperty(prefix = "marketdata.history.backfill", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class PriceHistoryBackfill implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(PriceHistoryBackfill.class);

    private static final int PRICE_SCALE = 6;
    private static final double SECONDS_PER_YEAR = 365.0 * 24 * 60 * 60;
    private static final long SECONDS_PER_DAY = 24L * 60 * 60;

    /**
     * Mixed into each instrument's configured seed so the generated history is deterministic
     * (every developer's database looks the same) but is not the same sequence the live engine
     * draws from that seed.
     */
    private static final long BACKFILL_SEED_MIX = 0x5F3B_1C27L;

    private final SimulatedInstrumentRepository instrumentRepository;
    private final PriceCandleRepository candleRepository;
    private final TransactionTemplate transactionTemplate;
    private final long stepSeconds;
    private final List<Tier> tiers;

    /**
     * Creates a new PriceHistoryBackfill.
     * @param instrumentRepository repository used to look up the instruments to backfill
     * @param candleRepository repository used to check for and persist candles
     * @param transactionTemplate template used to write each instrument in one transaction
     * @param stepSeconds the resolution, in seconds, of the underlying simulated walk
     * @param tierSpec the bucket widths to emit and how far back each reaches, as
     *                 {@code bucketSeconds:lookbackDays} pairs
     */
    public PriceHistoryBackfill(SimulatedInstrumentRepository instrumentRepository,
                                 PriceCandleRepository candleRepository,
                                 TransactionTemplate transactionTemplate,
                                 @Value("${marketdata.history.backfill.step-seconds:60}") long stepSeconds,
                                 @Value("${marketdata.history.backfill.tiers:86400:365,3600:90,300:7,60:2}")
                                 List<String> tierSpec) {
        if (stepSeconds <= 0) {
            throw new IllegalArgumentException("step-seconds must be positive");
        }
        this.instrumentRepository = instrumentRepository;
        this.candleRepository = candleRepository;
        this.transactionTemplate = transactionTemplate;
        this.stepSeconds = stepSeconds;
        this.tiers = parseTiers(tierSpec, stepSeconds);
    }

    /**
     * Backfills every active instrument that has no candles yet.
     * @param args the application arguments, unused
     */
    @Override
    public void run(ApplicationArguments args) {
        int backfilled = 0;
        for (SimulatedInstrument instrument : instrumentRepository.findByActiveTrue()) {
            if (candleRepository.existsByInstrument_InstrumentId(instrument.getInstrumentId())) {
                continue;
            }
            List<PriceCandle> candles = generate(instrument);
            transactionTemplate.executeWithoutResult(status -> candleRepository.saveAll(candles));
            backfilled++;
            log.info("Backfilled {} candles of price history for {}", candles.size(), instrument.getSymbol());
        }
        if (backfilled > 0) {
            log.info("Price history backfill complete for {} instrument(s)", backfilled);
        }
    }

    /**
     * Walks one instrument's simulated path and folds it into candles at every configured width.
     *
     * <p>The walk runs in epoch seconds and doubles rather than {@code OffsetDateTime} and
     * {@code BigDecimal}, and converts only at the point a candle is emitted: at a year of
     * minute-resolution steps this loop runs hundreds of thousands of times per instrument, and
     * allocating two objects per step dominated everything else it does.
     *
     * @param instrument the instrument to generate history for
     * @return the generated candles, in ascending bucket order per width
     */
    private List<PriceCandle> generate(SimulatedInstrument instrument) {
        // The walk stops at the start of the current finest bucket, leaving that bucket to the
        // live accumulator - otherwise both would write the same (instrument, start, width) row.
        long end = floorTo(OffsetDateTime.now().toEpochSecond(), stepSeconds);
        long maxLookbackDays = tiers.stream().mapToLong(Tier::lookbackDays).max().orElse(0);
        long start = end - maxLookbackDays * SECONDS_PER_DAY;

        RandomGenerator random = new Random(instrument.getRngSeed() ^ BACKFILL_SEED_MIX);
        double drift = instrument.getDrift().doubleValue();
        double volatility = instrument.getVolatility().doubleValue();
        double dt = stepSeconds / SECONDS_PER_YEAR;
        double price = instrument.getInitialPrice().doubleValue();

        int tierCount = tiers.size();
        long[] tierStarts = new long[tierCount];
        Accumulation[] open = new Accumulation[tierCount];
        for (int i = 0; i < tierCount; i++) {
            Tier tier = tiers.get(i);
            tierStarts[i] = ceilTo(end - tier.lookbackDays() * SECONDS_PER_DAY, tier.bucketSeconds());
        }

        List<PriceCandle> candles = new ArrayList<>();
        for (long at = start; at < end; at += stepSeconds) {
            price = GbmPriceGenerator.nextPrice(price, drift, volatility, dt, random);
            for (int i = 0; i < tierCount; i++) {
                if (at < tierStarts[i]) {
                    continue;
                }
                int bucketSeconds = tiers.get(i).bucketSeconds();
                long bucketStart = floorTo(at, bucketSeconds);
                Accumulation existing = open[i];
                if (existing == null) {
                    open[i] = new Accumulation(bucketStart, price);
                } else if (existing.bucketStart == bucketStart) {
                    existing.extend(price);
                } else {
                    candles.add(existing.toCandle(instrument, bucketSeconds));
                    open[i] = new Accumulation(bucketStart, price);
                }
            }
        }
        // Each tier's trailing bucket is deliberately left unwritten: it is still in progress,
        // and the live accumulator persists it when it rolls over.
        return candles;
    }

    /**
     * Parses the configured {@code bucketSeconds:lookbackDays} tier pairs.
     * @param tierSpec the raw configured pairs
     * @param stepSeconds the walk resolution every bucket width must be a multiple of
     * @return the parsed tiers
     */
    private static List<Tier> parseTiers(List<String> tierSpec, long stepSeconds) {
        if (tierSpec.isEmpty()) {
            throw new IllegalArgumentException("at least one backfill tier must be configured");
        }
        List<Tier> parsed = new ArrayList<>();
        for (String spec : tierSpec) {
            String[] parts = spec.trim().split(":");
            if (parts.length != 2) {
                throw new IllegalArgumentException(
                        "backfill tier must be bucketSeconds:lookbackDays, got: " + spec);
            }
            int bucketSeconds = Integer.parseInt(parts[0].trim());
            long lookbackDays = Long.parseLong(parts[1].trim());
            if (bucketSeconds <= 0 || lookbackDays <= 0) {
                throw new IllegalArgumentException("backfill tier values must be positive: " + spec);
            }
            // Buckets are aligned to the epoch, so a width that isn't a whole number of steps
            // would put bucket boundaries between two walk points and silently drop prices.
            if (bucketSeconds % stepSeconds != 0) {
                throw new IllegalArgumentException(
                        "backfill tier width " + bucketSeconds + "s must be a multiple of step-seconds "
                                + stepSeconds);
            }
            parsed.add(new Tier(bucketSeconds, lookbackDays));
        }
        return List.copyOf(parsed);
    }

    /**
     * Rounds an epoch second down to a bucket boundary.
     * @param epochSeconds the timestamp to round
     * @param bucketSeconds the bucket width, in seconds
     * @return the start of the bucket containing the timestamp
     */
    private static long floorTo(long epochSeconds, long bucketSeconds) {
        return epochSeconds - Math.floorMod(epochSeconds, bucketSeconds);
    }

    /**
     * Rounds an epoch second up to a bucket boundary, so a tier's first emitted bucket is one
     * the walk covers in full.
     * @param epochSeconds the timestamp to round
     * @param bucketSeconds the bucket width, in seconds
     * @return the first bucket boundary at or after the timestamp
     */
    private static long ceilTo(long epochSeconds, long bucketSeconds) {
        long floored = floorTo(epochSeconds, bucketSeconds);
        return floored == epochSeconds ? floored : floored + bucketSeconds;
    }

    /**
     * One output resolution: a bucket width and how far back it is generated.
     * @param bucketSeconds the bucket width, in seconds
     * @param lookbackDays how many days back this width reaches
     */
    private record Tier(int bucketSeconds, long lookbackDays) {
    }

    /**
     * Mutable in-progress OHLC accumulation, held as primitives for the duration of the walk.
     */
    private static final class Accumulation {
        private final long bucketStart;
        private final double open;
        private double high;
        private double low;
        private double close;

        private Accumulation(long bucketStart, double price) {
            this.bucketStart = bucketStart;
            this.open = price;
            this.high = price;
            this.low = price;
            this.close = price;
        }

        void extend(double price) {
            this.high = Math.max(high, price);
            this.low = Math.min(low, price);
            this.close = price;
        }

        PriceCandle toCandle(SimulatedInstrument instrument, int bucketSeconds) {
            return new PriceCandle(
                    instrument,
                    OffsetDateTime.ofInstant(Instant.ofEpochSecond(bucketStart), ZoneOffset.UTC),
                    bucketSeconds,
                    scale(open), scale(high), scale(low), scale(close));
        }

        private static BigDecimal scale(double price) {
            return BigDecimal.valueOf(price).setScale(PRICE_SCALE, RoundingMode.HALF_UP);
        }
    }
}
