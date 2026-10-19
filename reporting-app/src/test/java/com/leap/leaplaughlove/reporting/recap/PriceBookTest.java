package com.leap.leaplaughlove.reporting.recap;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("PriceBook")
class PriceBookTest {

    private static final LocalDate SEP_1 = LocalDate.of(2026, 9, 1);
    private static final LocalDate SEP_3 = LocalDate.of(2026, 9, 3);

    @Test
    @DisplayName("gives a day's own close, or the latest earlier one")
    void closeOnOrBefore() {
        PriceBook prices = new PriceBook(Map.of("AAPL", Map.of(SEP_1, new BigDecimal("200"), SEP_3, new BigDecimal("210"))));

        assertEquals(Optional.of(new BigDecimal("200")), prices.closeOn("AAPL", SEP_1));
        assertEquals(Optional.of(new BigDecimal("200")), prices.closeOn("AAPL", SEP_1.plusDays(1)));
        assertEquals(Optional.of(new BigDecimal("210")), prices.closeOn("AAPL", SEP_3.plusDays(10)));
    }

    @Test
    @DisplayName("has no price before a symbol's first candle, or for an unknown symbol")
    void noPrice() {
        PriceBook prices = new PriceBook(Map.of("AAPL", Map.of(SEP_3, new BigDecimal("210"))));

        assertTrue(prices.closeOn("AAPL", SEP_1).isEmpty());
        assertTrue(prices.closeOn("MSFT", SEP_3).isEmpty());
        assertTrue(PriceBook.empty().closeOn("AAPL", SEP_3).isEmpty());
    }

    @Test
    @DisplayName("keeps its own copy of the prices")
    void copiesInput() {
        Map<LocalDate, BigDecimal> closes = new HashMap<>(Map.of(SEP_1, new BigDecimal("200")));
        PriceBook prices = new PriceBook(Map.of("AAPL", closes));

        closes.put(SEP_3, new BigDecimal("999"));

        assertEquals(Optional.of(new BigDecimal("200")), prices.closeOn("AAPL", SEP_3));
    }
}
