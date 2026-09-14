package com.leap.leaplaughlove.marketdata.history;

import com.leap.leaplaughlove.marketdata.instrument.SimulatedInstrument;
import com.leap.leaplaughlove.marketdata.instrument.SimulatedInstrumentRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.leap.leaplaughlove.marketdata.simulation.PriceState;
import com.leap.leaplaughlove.marketdata.simulation.PriceTickEvent;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Aggregates the live tick stream into fixed-width OHLC candles instead of persisting every
 * tick, keeping table growth predictable (bucket count/day instead of tick count/day).
 */
@Component
public class PriceCandleAccumulator {

    private final SimulatedInstrumentRepository instrumentRepository;
    private final PriceCandleRepository candleRepository;
    private final long candleBucketSeconds;

    private final Map<String, SimulatedInstrument> instrumentsBySymbol = new ConcurrentHashMap<>();
    private final Map<String, CandleAccumulation> openCandles = new HashMap<>();

    /**
     * Creates a new PriceCandleAccumulator with the given collaborators and bucket width.
     * @param instrumentRepository repository used to look up active instruments at startup
     * @param candleRepository repository used to persist flushed candles
     * @param candleBucketSeconds the width, in seconds, of each OHLC bucket
     */
    public PriceCandleAccumulator(SimulatedInstrumentRepository instrumentRepository,
                                   PriceCandleRepository candleRepository,
                                   @Value("${marketdata.simulation.candle-bucket-seconds:60}") long candleBucketSeconds) {
        this.instrumentRepository = instrumentRepository;
        this.candleRepository = candleRepository;
        this.candleBucketSeconds = candleBucketSeconds;
    }

    /**
     * Loads every active instrument into the in-memory lookup used when flushing candles,
     * once the application context is ready.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void initialize() {
        instrumentRepository.findByActiveTrue()
                .forEach(instrument -> instrumentsBySymbol.put(instrument.getSymbol(), instrument));
    }

    /**
     * Folds a simulation tick into the open candle for its symbol's current bucket, flushing
     * the previous bucket first if the tick has rolled over into a new one.
     * @param event the simulation tick event to fold in
     */
    @EventListener
    public synchronized void onPriceTick(PriceTickEvent event) {
        PriceState state = event.priceState();
        OffsetDateTime bucketStart = currentBucketStart(state.asOf());
        CandleAccumulation existing = openCandles.get(state.symbol());
        if (existing == null) {
            openCandles.put(state.symbol(), CandleAccumulation.open(bucketStart, state.price()));
        } else if (existing.bucketStart().equals(bucketStart)) {
            openCandles.put(state.symbol(), existing.extend(state.price()));
        } else {
            flush(state.symbol(), existing);
            openCandles.put(state.symbol(), CandleAccumulation.open(bucketStart, state.price()));
        }
    }

    /**
     * Flushes and removes every open candle whose bucket has already elapsed, so a symbol
     * that stops ticking doesn't leave its last candle unpersisted indefinitely.
     */
    @Scheduled(fixedRateString = "${marketdata.simulation.candle-flush-interval-ms:5000}")
    public synchronized void flushStaleBuckets() {
        OffsetDateTime currentBucket = currentBucketStart(OffsetDateTime.now());
        openCandles.forEach((symbol, accumulation) -> {
            if (accumulation.bucketStart().isBefore(currentBucket)) {
                flush(symbol, accumulation);
            }
        });
        openCandles.values().removeIf(accumulation -> accumulation.bucketStart().isBefore(currentBucket));
    }

    /**
     * Persists a completed candle accumulation for a symbol.
     * @param symbol the instrument symbol the accumulation belongs to
     * @param accumulation the completed OHLC accumulation to persist
     */
    private void flush(String symbol, CandleAccumulation accumulation) {
        SimulatedInstrument instrument = instrumentsBySymbol.get(symbol);
        if (instrument == null) {
            return;
        }
        candleRepository.save(new PriceCandle(
                instrument, accumulation.bucketStart(),
                accumulation.open(), accumulation.high(), accumulation.low(), accumulation.close()));
    }

    /**
     * Rounds a timestamp down to the start of its candle bucket.
     * @param timestamp the timestamp to bucket
     * @return the start of the bucket containing the timestamp
     */
    private OffsetDateTime currentBucketStart(OffsetDateTime timestamp) {
        long epochSeconds = timestamp.toEpochSecond();
        long bucketEpochSeconds = epochSeconds - Math.floorMod(epochSeconds, candleBucketSeconds);
        return OffsetDateTime.ofInstant(Instant.ofEpochSecond(bucketEpochSeconds), timestamp.getOffset());
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
