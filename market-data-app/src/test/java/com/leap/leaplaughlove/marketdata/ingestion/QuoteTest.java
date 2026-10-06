package com.leap.leaplaughlove.marketdata.ingestion;

import com.leap.leaplaughlove.marketdata.instrument.SimulatedInstrument;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

@DisplayName("Quote and QuoteParseException Unit Tests")
class QuoteTest {

    @Test
    @DisplayName("a quote exposes the values it was built with")
    void quoteExposesValues() {
        SimulatedInstrument instrument = new SimulatedInstrument(UUID.randomUUID(), "AAPL", "Apple Inc.",
                new BigDecimal("100"), new BigDecimal("0.05"), new BigDecimal("0.2"), 42L, true);
        OffsetDateTime quoted = OffsetDateTime.parse("2026-01-01T10:00:00Z");
        OffsetDateTime received = quoted.plusSeconds(1);

        Quote quote = new Quote(instrument, new BigDecimal("99.9"), 10, new BigDecimal("100.1"), 20,
                new BigDecimal("100"), 5, "XNAS", 77L, quoted, received);

        assertNull(quote.getQuoteId());
        assertSame(instrument, quote.getInstrument());
        assertEquals(new BigDecimal("99.9"), quote.getBidPrice());
        assertEquals(10, quote.getBidSize());
        assertEquals(new BigDecimal("100.1"), quote.getAskPrice());
        assertEquals(20, quote.getAskSize());
        assertEquals(new BigDecimal("100"), quote.getLastPrice());
        assertEquals(5, quote.getLastSize());
        assertEquals("XNAS", quote.getExchange());
        assertEquals(77L, quote.getSequenceNumber());
        assertEquals(quoted, quote.getQuoteTimestamp());
        assertEquals(received, quote.getReceivedAt());
    }

    @Test
    @DisplayName("a parse exception keeps the offending line and the reason")
    void parseExceptionKeepsLine() {
        QuoteParseException ex = new QuoteParseException("garbage|line", "wrong field count");

        assertEquals("garbage|line", ex.getRawLine());
        assertEquals("wrong field count", ex.getMessage());
    }
}
