package com.leap.leaplaughlove.marketdata.ingestion;

import com.leap.leaplaughlove.marketdata.instrument.SimulatedInstrument;
import com.leap.leaplaughlove.marketdata.instrument.SimulatedInstrumentRepository;
import com.leap.leaplaughlove.marketdata.simulation.PriceState;
import com.leap.leaplaughlove.marketdata.simulation.PriceTickEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("QuoteIngestionService Unit Tests")
class QuoteIngestionServiceTest {

    // Verify quotes beyond the clock-skew allowance cannot be stored or published.
    @Test
    void rejectsFutureQuote() {
        service.ingest(line("AAPL", "150.20", "150.30", "150.25", 1, OffsetDateTime.now().plusHours(1)));
        assertTrue(stored().isEmpty());
        verify(eventPublisher, never()).publishEvent(any(QuoteIngestedEvent.class));
        assertTrue(service.latestAll().isEmpty());
    }

    // Verify malformed feed data is discarded without interrupting ingestion.
    @Test
    void rejectsMalformedQuote() {
        service.ingest("invalid|message");
        assertTrue(stored().isEmpty());
        verify(eventPublisher, never()).publishEvent(any(QuoteIngestedEvent.class));
        assertTrue(service.latestAll().isEmpty());
    }

    // Verify an older sequence cannot replace the last accepted market price.
    @Test
    void preservesLatestQuote() {
        service.ingest(line("AAPL", "150.20", "150.30", "150.25", 5, OffsetDateTime.now()));
        service.ingest(line("AAPL", "160.20", "160.30", "160.25", 4, OffsetDateTime.now()));
        assertEquals(0, new BigDecimal("150.25").compareTo(service.latest("AAPL").orElseThrow().lastPrice()));
        assertEquals(1, stored().size());
        verify(eventPublisher, times(1)).publishEvent(any(QuoteIngestedEvent.class));
    }

    // Verify rejecting an invalid quote does not consume its sequence number.
    @Test
    void acceptsCorrectedQuote() {
        service.ingest(line("AAPL", "151.00", "150.00", "150.25", 5, OffsetDateTime.now()));
        service.ingest(line("AAPL", "150.20", "150.30", "150.25", 5, OffsetDateTime.now()));
        assertTrue(service.latest("AAPL").isPresent());
        assertEquals(1, stored().size());
        verify(eventPublisher, times(1)).publishEvent(any(QuoteIngestedEvent.class));
    }

    @Mock
    private SimulatedInstrumentRepository instrumentRepository;

    @Mock
    private LatestQuoteWriter quoteWriter;

    @Mock
    private SimulatedQuoteFeedFormatter feedFormatter;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    private QuoteIngestionService service;

    @BeforeEach
    void setUp() {
        SimulatedInstrument aapl = new SimulatedInstrument(
                UUID.randomUUID(), "AAPL", "Apple Inc.",
                new BigDecimal("150.00"), new BigDecimal("0.07"), new BigDecimal("0.25"), 42L, true);
        when(instrumentRepository.findByActiveTrue()).thenReturn(List.of(aapl));

        service = new QuoteIngestionService(instrumentRepository, quoteWriter, feedFormatter,
                eventPublisher, 5, 30);
        service.initialize();
    }

    /** Runs a quote flush and returns what it stored. */
    @SuppressWarnings("unchecked")
    private List<Quote> stored() {
        service.flushLatestQuotes();
        ArgumentCaptor<Collection<Quote>> batch = ArgumentCaptor.forClass(Collection.class);
        verify(quoteWriter).upsert(batch.capture());
        return List.copyOf(batch.getValue());
    }

    private static String line(String symbol, String bid, String ask, String last, long seq, OffsetDateTime ts) {
        return String.join("|", symbol, bid, "100", ask, "100", last, "100", "SIMULATED",
                Long.toString(seq), ts.toString());
    }

    @Test
    @DisplayName("accepts a valid tick, persists it, updates latest, and publishes an event")
    void testOnPriceTickAcceptsValidQuote() {
        PriceState state = new PriceState("AAPL", new BigDecimal("150.250000"), OffsetDateTime.now());
        when(feedFormatter.format(state)).thenReturn(
                line("AAPL", "150.20", "150.30", "150.25", 1, OffsetDateTime.now()));

        service.onPriceTick(new PriceTickEvent(state));

        assertEquals(1, stored().size());
        ArgumentCaptor<QuoteIngestedEvent> captor = ArgumentCaptor.forClass(QuoteIngestedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertEquals("AAPL", captor.getValue().quoteState().symbol());

        assertTrue(service.latest("AAPL").isPresent());
        assertEquals(0, new BigDecimal("150.25").compareTo(service.latest("AAPL").get().lastPrice()));
    }

    @Test
    @DisplayName("rejects a quote for an unknown or inactive instrument")
    void testRejectsUnknownInstrument() {
        service.ingest(line("ZZZZ", "10.00", "10.10", "10.05", 1, OffsetDateTime.now()));

        assertTrue(stored().isEmpty());
        verify(eventPublisher, never()).publishEvent(any(QuoteIngestedEvent.class));
        assertTrue(service.latest("ZZZZ").isEmpty());
    }

    @Test
    @DisplayName("rejects a crossed quote (ask below bid)")
    void testRejectsCrossedQuote() {
        service.ingest(line("AAPL", "150.30", "150.20", "150.25", 1, OffsetDateTime.now()));

        assertTrue(stored().isEmpty());
        verify(eventPublisher, never()).publishEvent(any(QuoteIngestedEvent.class));
    }

    @Test
    @DisplayName("rejects a stale quote timestamp")
    void testRejectsStaleTimestamp() {
        service.ingest(line("AAPL", "150.20", "150.30", "150.25", 1, OffsetDateTime.now().minusSeconds(1000)));

        assertTrue(stored().isEmpty());
        verify(eventPublisher, never()).publishEvent(any(QuoteIngestedEvent.class));
    }

    @Test
    @DisplayName("rejects a non-increasing sequence number for the same symbol")
    void testRejectsNonIncreasingSequenceNumber() {
        service.ingest(line("AAPL", "150.20", "150.30", "150.25", 5, OffsetDateTime.now()));
        service.ingest(line("AAPL", "150.20", "150.30", "150.25", 5, OffsetDateTime.now()));

        assertEquals(1, stored().size());
        verify(eventPublisher, times(1)).publishEvent(any(QuoteIngestedEvent.class));
    }

    @Test
    @DisplayName("never writes to the database from the tick itself")
    void tickDoesNotWrite() {
        service.ingest(line("AAPL", "150.20", "150.30", "150.25", 1, OffsetDateTime.now()));

        verifyNoInteractions(quoteWriter);
        assertTrue(service.latest("AAPL").isPresent());
    }

    @Test
    @DisplayName("stores only the latest of several quotes accepted between flushes")
    void flushStoresOnlyTheLatestQuotePerSymbol() {
        service.ingest(line("AAPL", "150.20", "150.30", "150.25", 1, OffsetDateTime.now()));
        service.ingest(line("AAPL", "151.20", "151.30", "151.25", 2, OffsetDateTime.now()));

        List<Quote> stored = stored();
        assertEquals(1, stored.size());
        assertEquals(2, stored.get(0).getSequenceNumber());
    }

    @Test
    @DisplayName("keeps quotes a failed flush couldn't store for the next one, unless newer arrived")
    void failedFlushRetriesUnlessSuperseded() {
        service.ingest(line("AAPL", "150.20", "150.30", "150.25", 1, OffsetDateTime.now()));
        doThrow(new org.springframework.dao.DataAccessResourceFailureException("offline"))
                .when(quoteWriter).upsert(any());
        service.flushLatestQuotes();
        reset(quoteWriter);

        assertEquals(1, stored().get(0).getSequenceNumber());

        // A newer quote arrives while the failing write is in flight.
        doAnswer(invocation -> {
            service.ingest(line("AAPL", "152.20", "152.30", "152.25", 3, OffsetDateTime.now()));
            throw new org.springframework.dao.DataAccessResourceFailureException("offline");
        }).when(quoteWriter).upsert(any());
        service.ingest(line("AAPL", "151.20", "151.30", "151.25", 2, OffsetDateTime.now()));
        service.flushLatestQuotes();
        reset(quoteWriter);

        assertEquals(3, stored().get(0).getSequenceNumber());
    }
}
