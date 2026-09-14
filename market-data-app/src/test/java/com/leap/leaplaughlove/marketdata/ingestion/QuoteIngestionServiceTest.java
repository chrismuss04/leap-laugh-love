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
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("QuoteIngestionService Unit Tests")
class QuoteIngestionServiceTest {

    @Mock
    private SimulatedInstrumentRepository instrumentRepository;

    @Mock
    private QuoteRepository quoteRepository;

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

        service = new QuoteIngestionService(instrumentRepository, quoteRepository, feedFormatter,
                eventPublisher, 5, 30);
        service.initialize();
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

        verify(quoteRepository).save(any(Quote.class));
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

        verify(quoteRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any(QuoteIngestedEvent.class));
        assertTrue(service.latest("ZZZZ").isEmpty());
    }

    @Test
    @DisplayName("rejects a crossed quote (ask below bid)")
    void testRejectsCrossedQuote() {
        service.ingest(line("AAPL", "150.30", "150.20", "150.25", 1, OffsetDateTime.now()));

        verify(quoteRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any(QuoteIngestedEvent.class));
    }

    @Test
    @DisplayName("rejects a stale quote timestamp")
    void testRejectsStaleTimestamp() {
        service.ingest(line("AAPL", "150.20", "150.30", "150.25", 1, OffsetDateTime.now().minusSeconds(1000)));

        verify(quoteRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any(QuoteIngestedEvent.class));
    }

    @Test
    @DisplayName("rejects a non-increasing sequence number for the same symbol")
    void testRejectsNonIncreasingSequenceNumber() {
        service.ingest(line("AAPL", "150.20", "150.30", "150.25", 5, OffsetDateTime.now()));
        service.ingest(line("AAPL", "150.20", "150.30", "150.25", 5, OffsetDateTime.now()));

        verify(quoteRepository, times(1)).save(any(Quote.class));
        verify(eventPublisher, times(1)).publishEvent(any(QuoteIngestedEvent.class));
    }
}
