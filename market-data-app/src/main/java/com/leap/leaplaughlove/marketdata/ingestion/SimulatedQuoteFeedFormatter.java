package com.leap.leaplaughlove.marketdata.ingestion;

import com.leap.leaplaughlove.marketdata.simulation.PriceState;
import org.springframework.beans.factory.annotation.Autowired;
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
 *
 * <p>Pre-market and after-hours trading is thin, so the spread there is
 * {@code extended-spread-factor} times wider.
 */
@Component
public class SimulatedQuoteFeedFormatter {

    private static final int PRICE_SCALE = 6;
    private static final String DELIMITER = "|";

    private final BigDecimal halfSpreadFraction;
    private final BigDecimal extendedHalfSpreadFraction;
    private final long lotSize;
    private final String exchange;

    private final Map<String, AtomicLong> sequenceBySymbol = new ConcurrentHashMap<>();

    /**
     * Creates a new SimulatedQuoteFeedFormatter with the given spread, lot size, and
     * exchange tag.
     * @param spreadBps the full bid/ask spread to apply, in basis points of the last price
     * @param lotSize the size to report for every bid/ask/last quote
     * @param exchange the exchange tag to report on every formatted line
     */
    public SimulatedQuoteFeedFormatter(long spreadBps, long lotSize, String exchange) {
        this(spreadBps, lotSize, exchange, 1);
    }

    /**
     * Creates a new SimulatedQuoteFeedFormatter whose spread widens in extended hours.
     * @param spreadBps the full bid/ask spread to apply in regular hours, in basis points of the last price
     * @param lotSize the size to report for every bid/ask/last quote
     * @param exchange the exchange tag to report on every formatted line
     * @param extendedSpreadFactor how many times wider the spread is in pre-market and after-hours
     */
    @Autowired
    public SimulatedQuoteFeedFormatter(
            @Value("${marketdata.ingestion.spread-bps:5}") long spreadBps,
            @Value("${marketdata.ingestion.default-lot-size:100}") long lotSize,
            @Value("${marketdata.ingestion.exchange:SIMULATED}") String exchange,
            @Value("${marketdata.ingestion.extended-spread-factor:4}") long extendedSpreadFactor) {
        if (extendedSpreadFactor < 1) {
            throw new IllegalArgumentException("extended-spread-factor must be at least 1");
        }
        this.halfSpreadFraction = BigDecimal.valueOf(spreadBps)
                .divide(BigDecimal.valueOf(10_000L), 10, RoundingMode.HALF_UP)
                .divide(BigDecimal.valueOf(2), 10, RoundingMode.HALF_UP);
        this.extendedHalfSpreadFraction = halfSpreadFraction.multiply(BigDecimal.valueOf(extendedSpreadFactor));
        this.lotSize = lotSize;
        this.exchange = exchange;
    }

    /**
     * Formats a simulation tick as a raw pipe-delimited quote feed line, deriving a bid/ask
     * spread around the tick's price and assigning the next per-symbol sequence number.
     * @param state the simulation tick to format
     * @return the raw quote feed line
     */
    public String format(PriceState state) {
        BigDecimal lastPrice = state.price();
        BigDecimal halfSpread = state.session().isExtendedHours() ? extendedHalfSpreadFraction : halfSpreadFraction;
        BigDecimal spread = lastPrice.multiply(halfSpread).setScale(PRICE_SCALE, RoundingMode.HALF_UP);
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
