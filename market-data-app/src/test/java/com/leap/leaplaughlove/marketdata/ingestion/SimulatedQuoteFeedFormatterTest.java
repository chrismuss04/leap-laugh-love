package com.leap.leaplaughlove.marketdata.ingestion;

import com.leap.leaplaughlove.marketdata.simulation.PriceState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("SimulatedQuoteFeedFormatter Unit Tests")
class SimulatedQuoteFeedFormatterTest {

    private final SimulatedQuoteFeedFormatter formatter =
            new SimulatedQuoteFeedFormatter(5, 100, "SIMULATED");

    @Test
    @DisplayName("formats a bid/ask spread around the last price")
    void testFormatAppliesSpread() {
        PriceState state = new PriceState("AAPL", new BigDecimal("150.000000"), OffsetDateTime.now());

        QuoteFeedMessage message = QuoteFeedMessageParser.parse(formatter.format(state));

        assertTrue(message.bidPrice().compareTo(message.lastPrice()) < 0, "bid should be below last price");
        assertTrue(message.askPrice().compareTo(message.lastPrice()) > 0, "ask should be above last price");
        assertTrue(message.askPrice().compareTo(message.bidPrice()) > 0, "ask should be above bid");
        assertEquals("SIMULATED", message.exchange());
        assertEquals("AAPL", message.symbol());
    }

    @Test
    @DisplayName("sequence numbers increment monotonically per symbol")
    void testSequenceNumbersIncrementPerSymbol() {
        PriceState aapl = new PriceState("AAPL", new BigDecimal("150.00"), OffsetDateTime.now());
        PriceState msft = new PriceState("MSFT", new BigDecimal("380.00"), OffsetDateTime.now());

        long firstAapl = QuoteFeedMessageParser.parse(formatter.format(aapl)).sequenceNumber();
        long firstMsft = QuoteFeedMessageParser.parse(formatter.format(msft)).sequenceNumber();
        long secondAapl = QuoteFeedMessageParser.parse(formatter.format(aapl)).sequenceNumber();

        assertEquals(1, firstAapl);
        assertEquals(1, firstMsft);
        assertEquals(2, secondAapl);
    }
}
