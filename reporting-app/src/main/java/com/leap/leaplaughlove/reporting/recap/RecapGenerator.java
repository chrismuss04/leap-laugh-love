package com.leap.leaplaughlove.reporting.recap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Builds and stores every client's recap of a month: each client who had a fill before the month
 * ended, since they may have held something through it. Prices are fetched once for the whole
 * month, then every recap is worked out, ranked against the rest, and stored in one transaction.
 *
 * <p>All or nothing: if market data fails, nothing is stored and the month is tried again later
 * (see {@link RecapJob}). Synchronized, so the monthly run and a catch-up run never build the same
 * month at once.
 */
@Component
public class RecapGenerator {

    private static final Logger log = LoggerFactory.getLogger(RecapGenerator.class);

    private final RecapFillRepository fills;
    private final RecapSnapshotRepository snapshots;
    private final RecapPrices prices;
    private final RecapCalculator calculator;
    private final Clock clock;

    /**
     * Creates the generator.
     * @param fills reads clients' fills and the sectors
     * @param snapshots stores the recaps
     * @param prices fetches daily closes
     * @param calculator works out each recap and the rankings
     * @param clock stamps when the recaps were built
     */
    public RecapGenerator(RecapFillRepository fills, RecapSnapshotRepository snapshots, RecapPrices prices,
                          RecapCalculator calculator, Clock clock) {
        this.fills = fills;
        this.snapshots = snapshots;
        this.prices = prices;
        this.calculator = calculator;
        this.clock = clock;
    }

    /**
     * Builds and stores every client's recap of a month, replacing any stored before.
     * @param month the month to recap
     * @return how many recaps were stored
     * @throws RecapPricesUnavailableException when market data fails; nothing is stored
     */
    public synchronized int generate(YearMonth month) {
        Map<UUID, List<RecapFill>> fillsByClient = fills.fillsThrough(month);
        if (fillsByClient.isEmpty()) {
            log.info("No client had traded by the end of {}: no recaps to build", month);
            return 0;
        }

        Set<String> symbols = new TreeSet<>();
        fillsByClient.values().forEach(clientFills -> symbols.addAll(calculator.symbolsToPrice(month, clientFills)));
        PriceBook priceBook = prices.load(month, symbols);
        Map<String, String> sectors = fills.sectors();

        Map<UUID, RecapPayload> recaps = new HashMap<>();
        Map<UUID, BigDecimal> tradingReturns = new HashMap<>();
        fillsByClient.forEach((clientId, clientFills) -> {
            RecapPayload recap = calculator.calculate(month, clientFills, priceBook, sectors);
            recaps.put(clientId, recap);
            tradingReturns.put(clientId, recap.tradingReturn().returnPct());
        });
        Map<UUID, Integer> topPercents = calculator.topPercents(tradingReturns);
        topPercents.forEach((clientId, topPercent) -> recaps.compute(clientId,
                (id, recap) -> recap.withTopPercent(topPercent)));

        snapshots.saveMonth(month, recaps, OffsetDateTime.now(clock));
        log.info("Stored {} recaps of {}, priced from {} symbols", recaps.size(), month, symbols.size());
        return recaps.size();
    }
}
