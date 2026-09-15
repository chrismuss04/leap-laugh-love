package com.leap.leaplaughlove.marketdata.ingestion;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DisplayName("QuoteFeedMessageParser Unit Tests")
class QuoteFeedMessageParserTest {

    private static final String VALID_LINE =
            "aapl|150.200000|100|150.300000|100|150.250000|100|SIMULATED|1|2026-09-14T10:15:30Z";

    @Test
    @DisplayName("parses a well-formed line into a QuoteFeedMessage")
    void testParseValidLine() {
        QuoteFeedMessage message = QuoteFeedMessageParser.parse(VALID_LINE);

        assertEquals("AAPL", message.symbol());
        assertEquals(0, new BigDecimal("150.200000").compareTo(message.bidPrice()));
        assertEquals(100, message.bidSize());
        assertEquals(0, new BigDecimal("150.300000").compareTo(message.askPrice()));
        assertEquals(100, message.askSize());
        assertEquals(0, new BigDecimal("150.250000").compareTo(message.lastPrice()));
        assertEquals(100, message.lastSize());
        assertEquals("SIMULATED", message.exchange());
        assertEquals(1, message.sequenceNumber());
    }

    @Test
    @DisplayName("rejects a line with the wrong number of fields")
    void testRejectsWrongFieldCount() {
        QuoteParseException ex = assertThrows(QuoteParseException.class,
                () -> QuoteFeedMessageParser.parse("AAPL|150.20|100"));
        assertEquals(true, ex.getMessage().contains("expected 10 fields"));
    }

    @Test
    @DisplayName("rejects a non-numeric price field")
    void testRejectsNonNumericPrice() {
        String line = "AAPL|not-a-number|100|150.30|100|150.25|100|SIMULATED|1|2026-09-14T10:15:30Z";

        QuoteParseException ex = assertThrows(QuoteParseException.class,
                () -> QuoteFeedMessageParser.parse(line));
        assertEquals(true, ex.getMessage().contains("bidPrice"));
    }

    @Test
    @DisplayName("rejects a zero or negative price field")
    void testRejectsNonPositivePrice() {
        String line = "AAPL|0|100|150.30|100|150.25|100|SIMULATED|1|2026-09-14T10:15:30Z";

        QuoteParseException ex = assertThrows(QuoteParseException.class,
                () -> QuoteFeedMessageParser.parse(line));
        assertEquals(true, ex.getMessage().contains("must be positive"));
    }

    @Test
    @DisplayName("rejects a negative size field")
    void testRejectsNegativeSize() {
        String line = "AAPL|150.20|-1|150.30|100|150.25|100|SIMULATED|1|2026-09-14T10:15:30Z";

        QuoteParseException ex = assertThrows(QuoteParseException.class,
                () -> QuoteFeedMessageParser.parse(line));
        assertEquals(true, ex.getMessage().contains("bidSize"));
    }

    @Test
    @DisplayName("rejects an unparseable timestamp")
    void testRejectsBadTimestamp() {
        String line = "AAPL|150.20|100|150.30|100|150.25|100|SIMULATED|1|not-a-timestamp";

        QuoteParseException ex = assertThrows(QuoteParseException.class,
                () -> QuoteFeedMessageParser.parse(line));
        assertEquals(true, ex.getMessage().contains("quoteTimestamp"));
    }

    @Test
    @DisplayName("rejects a blank line")
    void testRejectsBlankLine() {
        assertThrows(QuoteParseException.class, () -> QuoteFeedMessageParser.parse("   "));
    }
}
