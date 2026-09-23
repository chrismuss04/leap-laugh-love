package com.leap.leaplaughlove.marketdata.history;

import com.leap.leaplaughlove.marketdata.instrument.SimulatedInstrument;
import com.leap.leaplaughlove.marketdata.instrument.SimulatedInstrumentRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.leap.leaplaughlove.marketdata.simulation.MarketSimulationEngine;
import com.leap.leaplaughlove.marketdata.simulation.PriceState;
import com.leap.leaplaughlove.marketdata.simulation.PriceTickEvent;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Aggregates the live tick stream into fixed-width OHLC candles instead of persisting every
 * tick, keeping table growth predictable (bucket count/day instead of tick count/day).
 *
 * <p>Every configured bucket width is accumulated in parallel from the same tick stream, so a
 * chart over any range reads candles that stay current rather than only the finest width. The
 * wider rollups are what make long ranges affordable: a year of 60s candles is ~525k rows per
 * instrument, the same year of daily candles is 365. A candle is written only when its bucket
 * rolls over, so a daily candle costs one insert per day, not one per tick.
 *
 * <p>Completed candles are queued rather than saved where they roll over, and persisted in one
 * batch by the scheduled flush. Spring publishes events synchronously, so saving inline meant
 * a database round trip per rolled-over bucket on the simulation's tick thread, inside this
 * class's monitor - at every minute boundary that is one serialised write per instrument
 * competing with the tick interval. The queue puts all I/O on the flush thread and lets it
 * batch. The cost is that a crash can lose up to one flush interval of completed candles.
 */
@Component
public class PriceCandleAccumulator {

    private final SimulatedInstrumentRepository instrumentRepository;
    private final PriceCandleRepository candleRepository;
    private final List<Integer> candleBucketSeconds;

    private final Map<String, SimulatedInstrument> instrumentsBySymbol = new ConcurrentHashMap<>();
    private final Map<BucketKey, CandleAccumulation> openCandles = new HashMap<>();
    private final Queue<PriceCandle> pendingCandles = new ConcurrentLinkedQueue<>();

    /**
     * Creates a new PriceCandleAccumulator with the given collaborators and bucket widths.
     * @param instrumentRepository repository used to look up active instruments at startup
     * @param candleRepository repository used to persist flushed candles
     * @param candleBucketSeconds the widths, in seconds, of the OHLC buckets to accumulate
     */
    public PriceCandleAccumulator(SimulatedInstrumentRepository instrumentRepository,
                                   PriceCandleRepository candleRepository,
                                   @Value("${marketdata.simulation.candle-bucket-seconds:60,300,3600,86400}")
                                   List<Integer> candleBucketSeconds) {
        if (candleBucketSeconds.isEmpty()) {
            throw new IllegalArgumentException("at least one candle bucket width must be configured");
        }
        if (candleBucketSeconds.stream().anyMatch(width -> width == null || width <= 0)) {
            throw new IllegalArgumentException("candle bucket widths must be positive");
        }
        this.instrumentRepository = instrumentRepository;
        this.candleRepository = candleRepository;
        this.candleBucketSeconds = List.copyOf(candleBucketSeconds);
    }

    /**
     * Loads every active instrument into the in-memory lookup used when flushing candles,
     * once the application context is ready, before the simulation engine starts ticking.
     */
    @EventListener(ApplicationReadyEvent.class)
    @Order(MarketSimulationEngine.TICK_CONSUMER_ORDER)
    public void initialize() {
        instrumentRepository.findByActiveTrue()
                .forEach(instrument -> instrumentsBySymbol.put(instrument.getSymbol(), instrument));
    }

    /**
     * Folds a simulation tick into the open candle for each configured bucket width, flushing
     * any bucket the tick has rolled past first.
     * @param event the simulation tick event to fold in
     */
    @EventListener
    public synchronized void onPriceTick(PriceTickEvent event) {
        PriceState state = event.priceState();
        for (int width : candleBucketSeconds) {
            BucketKey key = new BucketKey(state.symbol(), width);
            OffsetDateTime bucketStart = bucketStart(state.asOf(), width);
            CandleAccumulation existing = openCandles.get(key);
            if (existing == null) {
                openCandles.put(key, CandleAccumulation.open(bucketStart, state.price()));
            } else if (existing.bucketStart().equals(bucketStart)) {
                openCandles.put(key, existing.extend(state.price()));
            } else {
                flush(key, existing);
                openCandles.put(key, CandleAccumulation.open(bucketStart, state.price()));
            }
        }
    }

    /**
     * Closes every open candle whose bucket has already elapsed - so a symbol that stops ticking
     * doesn't leave its last candle unqueued indefinitely - and then writes everything queued.
     */
    @Scheduled(fixedRateString = "${marketdata.simulation.candle-flush-interval-ms:5000}")
    public void flushStaleBuckets() {
        closeElapsedBuckets();
        persistPending();
    }

    /**
     * Queues and removes every open candle whose bucket has already elapsed.
     */
    private synchronized void closeElapsedBuckets() {
        OffsetDateTime now = OffsetDateTime.now();
        List<BucketKey> elapsed = new ArrayList<>();
        openCandles.forEach((key, accumulation) -> {
            if (accumulation.bucketStart().isBefore(bucketStart(now, key.bucketSeconds()))) {
                flush(key, accumulation);
                elapsed.add(key);
            }
        });
        elapsed.forEach(openCandles::remove);
    }

    /**
     * Writes every queued candle in one batch. Deliberately not synchronized: the database round
     * trip must not hold the monitor that {@link #onPriceTick} needs, or the simulation's tick
     * thread would block on it.
     */
    private void persistPending() {
        List<PriceCandle> batch = new ArrayList<>();
        for (PriceCandle candle = pendingCandles.poll(); candle != null; candle = pendingCandles.poll()) {
            batch.add(candle);
        }
        if (!batch.isEmpty()) {
            candleRepository.saveAll(batch);
        }
    }

    /**
     * Queues a completed candle accumulation for the next batch write.
     * @param key the symbol and bucket width the accumulation belongs to
     * @param accumulation the completed OHLC accumulation to queue
     */
    private void flush(BucketKey key, CandleAccumulation accumulation) {
        SimulatedInstrument instrument = instrumentsBySymbol.get(key.symbol());
        if (instrument == null) {
            return;
        }
        pendingCandles.add(new PriceCandle(
                instrument, accumulation.bucketStart(), key.bucketSeconds(),
                accumulation.open(), accumulation.high(), accumulation.low(), accumulation.close()));
    }

    /**
     * Rounds a timestamp down to the start of its bucket at the given width.
     * @param timestamp the timestamp to bucket
     * @param bucketSeconds the bucket width, in seconds
     * @return the start of the bucket containing the timestamp
     */
    private static OffsetDateTime bucketStart(OffsetDateTime timestamp, int bucketSeconds) {
        long epochSeconds = timestamp.toEpochSecond();
        long bucketEpochSeconds = epochSeconds - Math.floorMod(epochSeconds, (long) bucketSeconds);
        return OffsetDateTime.ofInstant(Instant.ofEpochSecond(bucketEpochSeconds), timestamp.getOffset());
    }

    /**
     * Identifies one in-progress accumulation: a symbol at one bucket width.
     * @param symbol the instrument symbol
     * @param bucketSeconds the bucket width, in seconds
     */
    private record BucketKey(String symbol, int bucketSeconds) {
    }

    /**
     * In-progress OHLC accumulation for one symbol's current candle bucket.
     * @param bucketStart the start of the bucket being accumulated
     * @param open the opening price of the bucket
     * @param high the highest price seen so far in the bucket
     * @param low the lowest price seen so far in the bucket
     * @param close the most recent price seen in the bucket
     */
    private record CandleAccumulation(OffsetDateTime bucketStart, BigDecimal open, BigDecimal high,
                                       BigDecimal low, BigDecimal close) {
        /**
         * Opens a new accumulation with a single price as its open/high/low/close.
         * @param bucketStart the start of the bucket
         * @param price the first price observed in the bucket
         * @return the opened accumulation
         */
        static CandleAccumulation open(OffsetDateTime bucketStart, BigDecimal price) {
            return new CandleAccumulation(bucketStart, price, price, price, price);
        }

        /**
         * Extends this accumulation with another price observed in the same bucket.
         * @param price the next price observed in the bucket
         * @return the extended accumulation
         */
        CandleAccumulation extend(BigDecimal price) {
            return new CandleAccumulation(bucketStart, open, high.max(price), low.min(price), price);
        }
    }
}
