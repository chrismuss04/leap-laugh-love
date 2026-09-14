package com.leap.leaplaughlove.marketdata.ingestion;

import com.leap.leaplaughlove.marketdata.simulation.PriceState;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Simulates a raw exchange quote feed line by deriving a bid/ask spread around the
 * simulation engine's last price (which has no spread of its own). Isolated so this is the
 * one class to replace if a real feed is wired in later - everything downstream of it only
 * ever sees the raw wire format.
 */
@Component
public class SimulatedQuoteFeedFormatter {

    private static final int PRICE_SCALE = 6;
    private static final String DELIMITER = "|";

    private final BigDecimal halfSpreadFraction;
    private final long lotSize;
    private final String exchange;

    private final Map<String, AtomicLong> sequenceBySymbol = new ConcurrentHashMap<>();

    public SimulatedQuoteFeedFormatter(
            @Value("${marketdata.ingestion.spread-bps:5}") long spreadBps,
            @Value("${marketdata.ingestion.default-lot-size:100}") long lotSize,
            @Value("${marketdata.ingestion.exchange:SIMULATED}") String exchange) {
        this.halfSpreadFraction = BigDecimal.valueOf(spreadBps)
                .divide(BigDecimal.valueOf(10_000L), 10, RoundingMode.HALF_UP)
                .divide(BigDecimal.valueOf(2), 10, RoundingMode.HALF_UP);
        this.lotSize = lotSize;
        this.exchange = exchange;
    }

    public String format(PriceState state) {
        BigDecimal lastPrice = state.price();
        BigDecimal spread = lastPrice.multiply(halfSpreadFraction).setScale(PRICE_SCALE, RoundingMode.HALF_UP);
        BigDecimal bidPrice = lastPrice.subtract(spread).setScale(PRICE_SCALE, RoundingMode.HALF_UP);
        BigDecimal askPrice = lastPrice.add(spread).setScale(PRICE_SCALE, RoundingMode.HALF_UP);
        long sequenceNumber = sequenceBySymbol
                .computeIfAbsent(state.symbol(), s -> new AtomicLong())
                .incrementAndGet();

        return String.join(DELIMITER,
                state.symbol(),
                bidPrice.toPlainString(),
                Long.toString(lotSize),
                askPrice.toPlainString(),
                Long.toString(lotSize),
                lastPrice.toPlainString(),
                Long.toString(lotSize),
                exchange,
                Long.toString(sequenceNumber),
                state.asOf().toString());
    }
}
