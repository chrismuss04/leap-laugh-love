package com.leap.leaplaughlove.marketdata.ingestion;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("QuoteFeedMessageParser field validation")
class QuoteFeedMessageParserEdgeTest {

    private static final String TEMPLATE =
            "%s|150.2|100|150.3|100|150.25|100|SIMULATED|1|2026-09-14T10:15:30Z";

    private static QuoteParseException failure(String line) {
        return assertThrows(QuoteParseException.class, () -> QuoteFeedMessageParser.parse(line));
    }

    @Test
    @DisplayName("refuses a null line")
    void nullLine() {
        QuoteParseException ex = failure(null);
        assertNull(ex.getRawLine());
        assertEquals("line is blank", ex.getMessage());
    }

    @Test
    @DisplayName("refuses a blank symbol or exchange")
    void blankTextFields() {
        assertTrue(failure(TEMPLATE.formatted(" ")).getMessage().contains("symbol must not be blank"));
        assertTrue(failure("AAPL|150.2|100|150.3|100|150.25|100| |1|2026-09-14T10:15:30Z")
                .getMessage().contains("exchange must not be blank"));
    }

    @Test
    @DisplayName("refuses a size that is not an integer")
    void nonIntegerSize() {
        assertTrue(failure("AAPL|150.2|ten|150.3|100|150.25|100|SIMULATED|1|2026-09-14T10:15:30Z")
                .getMessage().contains("bidSize is not a valid integer"));
    }

    @Test
    @DisplayName("refuses a sequence number that is not an integer")
    void nonIntegerSequence() {
        assertTrue(failure("AAPL|150.2|100|150.3|100|150.25|100|SIMULATED|first|2026-09-14T10:15:30Z")
                .getMessage().contains("sequenceNumber is not a valid integer"));
    }

    @Test
    @DisplayName("refuses a negative size")
    void negativeSize() {
        assertTrue(failure("AAPL|150.2|100|150.3|-1|150.25|100|SIMULATED|1|2026-09-14T10:15:30Z")
                .getMessage().contains("askSize must not be negative"));
    }
}
