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

    public PriceCandleAccumulator(SimulatedInstrumentRepository instrumentRepository,
                                   PriceCandleRepository candleRepository,
                                   @Value("${marketdata.simulation.candle-bucket-seconds:60}") long candleBucketSeconds) {
        this.instrumentRepository = instrumentRepository;
        this.candleRepository = candleRepository;
        this.candleBucketSeconds = candleBucketSeconds;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void initialize() {
        instrumentRepository.findByActiveTrue()
                .forEach(instrument -> instrumentsBySymbol.put(instrument.getSymbol(), instrument));
    }

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

    private void flush(String symbol, CandleAccumulation accumulation) {
        SimulatedInstrument instrument = instrumentsBySymbol.get(symbol);
        if (instrument == null) {
            return;
        }
        candleRepository.save(new PriceCandle(
                instrument, accumulation.bucketStart(),
                accumulation.open(), accumulation.high(), accumulation.low(), accumulation.close()));
    }

    private OffsetDateTime currentBucketStart(OffsetDateTime timestamp) {
        long epochSeconds = timestamp.toEpochSecond();
        long bucketEpochSeconds = epochSeconds - Math.floorMod(epochSeconds, candleBucketSeconds);
        return OffsetDateTime.ofInstant(Instant.ofEpochSecond(bucketEpochSeconds), timestamp.getOffset());
    }

    private record CandleAccumulation(OffsetDateTime bucketStart, BigDecimal open, BigDecimal high,
                                       BigDecimal low, BigDecimal close) {
        static CandleAccumulation open(OffsetDateTime bucketStart, BigDecimal price) {
            return new CandleAccumulation(bucketStart, price, price, price, price);
        }

        CandleAccumulation extend(BigDecimal price) {
            return new CandleAccumulation(bucketStart, open, high.max(price), low.min(price), price);
        }
    }
}
