package com.leap.leaplaughlove.order.execution;

import com.leap.leaplaughlove.order.order.Order;
import com.leap.leaplaughlove.order.quote.QuoteSnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ExecutionQuote Tests")
class ExecutionQuoteTest {

    private static final OffsetDateTime AT = OffsetDateTime.parse("2026-10-08T14:02:05Z");

    private static ExecutionQuote quote(String bid, String ask, String last) {
        return new ExecutionQuote(bid == null ? null : new BigDecimal(bid), ask == null ? null : new BigDecimal(ask),
                last == null ? null : new BigDecimal(last), AT, "NASDAQ");
    }

    @Test
    @DisplayName("Keeps bid, ask, last, time and exchange from the live quote")
    void ofSnapshot() {
        ExecutionQuote quote = ExecutionQuote.of(new QuoteSnapshot("AAPL", new BigDecimal("149.95"), 100L,
                new BigDecimal("150.05"), 200L, new BigDecimal("150.00"), 50L, "NASDAQ", AT));

        assertEquals(new BigDecimal("149.95"), quote.getBid());
        assertEquals(new BigDecimal("150.05"), quote.getAsk());
        assertEquals(new BigDecimal("150.00"), quote.getLast());
        assertEquals(AT, quote.getQuotedAt());
        assertEquals("NASDAQ", quote.getExchange());
    }

    @Test
    @DisplayName("A BUY executes at the ask and a SELL at the bid, to 4 decimal places")
    void executablePriceBySide() {
        ExecutionQuote quote = quote("149.95", "150.05", "150.00");

        assertEquals(new BigDecimal("150.0500"), quote.executablePrice(Order.Side.BUY));
        assertEquals(new BigDecimal("149.9500"), quote.executablePrice(Order.Side.SELL));
    }

    @Test
    @DisplayName("Falls back to the last trade when that side has no positive price")
    void fallsBackToLast() {
        assertEquals(new BigDecimal("150.0000"), quote("149.95", null, "150.00").executablePrice(Order.Side.BUY));
        assertEquals(new BigDecimal("150.0000"), quote("0", "150.05", "150.00").executablePrice(Order.Side.SELL));
    }

    @Test
    @DisplayName("No usable price gives null")
    void noUsablePrice() {
        assertNull(quote(null, null, null).executablePrice(Order.Side.BUY));
        assertNull(quote("0", "0", "0").executablePrice(Order.Side.SELL));
    }
}
