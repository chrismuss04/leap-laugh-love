package com.leap.leaplaughlove.marketdata.ingestion;

import com.leap.leaplaughlove.marketdata.instrument.SimulatedInstrument;
import com.leap.leaplaughlove.marketdata.instrument.SimulatedInstrumentRepository;
import com.leap.leaplaughlove.marketdata.simulation.MarketSimulationEngine;
import com.leap.leaplaughlove.marketdata.simulation.PriceTickEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Ingests raw quote feed messages (today, formatted from the live simulation tick stream by
 * SimulatedQuoteFeedFormatter - swappable for a real feed later): parses, validates,
 * persists, and republishes each accepted quote. Invalid, unknown-symbol, or out-of-order
 * messages are logged and dropped rather than allowed to interrupt the pipeline.
 */
@Component
public class QuoteIngestionService {

    private static final Logger log = LoggerFactory.getLogger(QuoteIngestionService.class);

    private final SimulatedInstrumentRepository instrumentRepository;
    private final QuoteRepository quoteRepository;
    private final SimulatedQuoteFeedFormatter feedFormatter;
    private final ApplicationEventPublisher eventPublisher;
    private final long maxClockSkewSeconds;
    private final long staleAfterSeconds;

    private final Map<String, SimulatedInstrument> instrumentsBySymbol = new ConcurrentHashMap<>();
    private final Map<String, QuoteState> latestBySymbol = new ConcurrentHashMap<>();
    private final Map<String, Long> lastSequenceBySymbol = new ConcurrentHashMap<>();

    /**
     * Creates a new QuoteIngestionService with the given collaborators and validation
     * thresholds.
     * @param instrumentRepository repository used to look up active instruments at startup
     * @param quoteRepository repository used to persist accepted quotes
     * @param feedFormatter formatter used to turn simulation ticks into raw feed lines
     * @param eventPublisher publisher used to broadcast {@link QuoteIngestedEvent}s
     * @param maxClockSkewSeconds how far into the future a quote's timestamp may be before
     *     it is rejected
     * @param staleAfterSeconds how far into the past a quote's timestamp may be before it is
     *     rejected as stale
     */
    public QuoteIngestionService(SimulatedInstrumentRepository instrumentRepository,
                                  QuoteRepository quoteRepository,
                                  SimulatedQuoteFeedFormatter feedFormatter,
                                  ApplicationEventPublisher eventPublisher,
                                  @Value("${marketdata.ingestion.max-clock-skew-seconds:5}") long maxClockSkewSeconds,
                                  @Value("${marketdata.ingestion.stale-after-seconds:30}") long staleAfterSeconds) {
        this.instrumentRepository = instrumentRepository;
        this.quoteRepository = quoteRepository;
        this.feedFormatter = feedFormatter;
        this.eventPublisher = eventPublisher;
        this.maxClockSkewSeconds = maxClockSkewSeconds;
        this.staleAfterSeconds = staleAfterSeconds;
    }

    /**
     * Loads every active instrument into the in-memory lookup used to validate incoming
     * quote symbols, once the application context is ready. Runs before the simulation engine
     * starts ticking, or every tick until this finished would be rejected as an unknown symbol.
     */
    @EventListener(ApplicationReadyEvent.class)
    @Order(MarketSimulationEngine.TICK_CONSUMER_ORDER)
    public void initialize() {
        instrumentRepository.findByActiveTrue()
                .forEach(instrument -> instrumentsBySymbol.put(instrument.getSymbol(), instrument));
    }

    /**
     * Formats the given simulation tick as a raw feed line and ingests it.
     * @param event the simulation tick event to derive a quote message from
     */
    @EventListener
    public void onPriceTick(PriceTickEvent event) {
        ingest(feedFormatter.format(event.priceState()));
    }

    /**
     * Parses, validates, and (if valid) persists a raw feed line. Package-visible so
     * validation/rejection paths can be exercised directly from tests with a crafted raw
     * line, without needing a PriceTickEvent to produce every edge case.
     */
    void ingest(String rawLine) {
        QuoteFeedMessage message;
        try {
            message = QuoteFeedMessageParser.parse(rawLine);
        } catch (QuoteParseException ex) {
            log.warn("Rejected unparseable quote message [{}]: {}", rawLine, ex.getMessage());
            return;
        }

        String rejectionReason = validate(message);
        if (rejectionReason != null) {
            log.warn("Rejected quote message [{}]: {}", rawLine, rejectionReason);
            return;
        }

        SimulatedInstrument instrument = instrumentsBySymbol.get(message.symbol());
        if (instrument == null) {
            log.warn("Rejected quote message [{}]: unknown or inactive instrument {}",
                    rawLine, message.symbol());
            return;
        }

        OffsetDateTime receivedAt = OffsetDateTime.now();
        quoteRepository.save(new Quote(instrument, message.bidPrice(), message.bidSize(),
                message.askPrice(), message.askSize(), message.lastPrice(), message.lastSize(),
                message.exchange(), message.sequenceNumber(), message.quoteTimestamp(), receivedAt));

        lastSequenceBySymbol.put(message.symbol(), message.sequenceNumber());
        QuoteState quoteState = new QuoteState(message.symbol(), message.bidPrice(), message.bidSize(),
                message.askPrice(), message.askSize(), message.lastPrice(), message.lastSize(),
                message.exchange(), message.sequenceNumber(), message.quoteTimestamp(), receivedAt);
        latestBySymbol.put(message.symbol(), quoteState);
        eventPublisher.publishEvent(new QuoteIngestedEvent(quoteState));
    }

    /**
     * Validates business rules for a structurally-valid quote message: bid/ask ordering,
     * timestamp freshness, and sequence-number ordering.
     * @param message the parsed quote message to validate
     * @return a human-readable rejection reason, or {@code null} if the message is valid
     */
    private String validate(QuoteFeedMessage message) {
        if (message.askPrice().compareTo(message.bidPrice()) < 0) {
            return "crossed quote: ask (" + message.askPrice() + ") < bid (" + message.bidPrice() + ")";
        }

        OffsetDateTime now = OffsetDateTime.now();
        if (message.quoteTimestamp().isAfter(now.plusSeconds(maxClockSkewSeconds))) {
            return "quoteTimestamp is too far in the future: " + message.quoteTimestamp();
        }
        if (message.quoteTimestamp().isBefore(now.minusSeconds(staleAfterSeconds))) {
            return "quoteTimestamp is stale: " + message.quoteTimestamp();
        }

        Long lastSequence = lastSequenceBySymbol.get(message.symbol());
        if (lastSequence != null && message.sequenceNumber() <= lastSequence) {
            return "sequenceNumber " + message.sequenceNumber()
                    + " is not greater than last accepted " + lastSequence;
        }

        return null;
    }

    /**
     * Gets the latest accepted quote for the given instrument symbol.
     * @param symbol the instrument symbol
     * @return the latest quote state for the symbol, if any has been ingested
     */
    public Optional<QuoteState> latest(String symbol) {
        return Optional.ofNullable(latestBySymbol.get(symbol));
    }

    /**
     * Gets the latest accepted quote for every symbol seen so far.
     * @return the latest quote state for each symbol
     */
    public List<QuoteState> latestAll() {
        return List.copyOf(latestBySymbol.values());
    }
}
