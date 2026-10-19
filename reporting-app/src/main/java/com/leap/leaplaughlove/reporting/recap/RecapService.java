package com.leap.leaplaughlove.reporting.recap;

import com.leap.leaplaughlove.reporting.recap.RecapSnapshotRepository.StoredRecap;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Serves a client's recap of last month: the stored one when the month's job has built it, or one
 * worked out for this request when it hasn't yet, as in the half hour after a month ends. A recap
 * worked out on request has no ranking, since that needs every client's, and isn't stored, so it
 * never stands in for the job's.
 */
@Service
public class RecapService {

    private final RecapSnapshotRepository snapshots;
    private final RecapFillRepository fills;
    private final RecapPrices prices;
    private final RecapCalculator calculator;
    private final Clock clock;

    /**
     * Creates the service.
     * @param snapshots reads stored recaps
     * @param fills reads the client's fills and the sectors
     * @param prices fetches daily closes
     * @param calculator works out a recap
     * @param clock the UTC clock months are measured on
     */
    public RecapService(RecapSnapshotRepository snapshots, RecapFillRepository fills, RecapPrices prices,
                        RecapCalculator calculator, Clock clock) {
        this.snapshots = snapshots;
        this.fills = fills;
        this.prices = prices;
        this.calculator = calculator;
        this.clock = clock;
    }

    /**
     * The client's recap of last month. A client who has never traded gets a quiet recap.
     * @param clientId the client
     * @return the recap
     * @throws RecapPricesUnavailableException when it has to be worked out and market data fails
     */
    public RecapResponse latest(UUID clientId) {
        YearMonth month = YearMonth.now(clock).minusMonths(1);
        // A recap stored in an older JSON shape is worked out again rather than misread.
        Optional<StoredRecap> stored = snapshots.find(clientId, month)
                .filter(recap -> recap.schemaVersion() == RecapCalculator.SCHEMA_VERSION);
        if (stored.isPresent()) {
            return new RecapResponse(month, stored.get().generatedAt(), true, snapshots.payload(stored.get()));
        }

        List<RecapFill> clientFills = fills.fillsThrough(clientId, month);
        PriceBook priceBook = prices.load(month, calculator.symbolsToPrice(month, clientFills));
        RecapPayload recap = calculator.calculate(month, clientFills, priceBook, fills.sectors());
        return new RecapResponse(month, OffsetDateTime.now(clock), false, recap);
    }
}
