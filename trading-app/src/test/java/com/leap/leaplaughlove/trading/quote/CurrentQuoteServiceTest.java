package com.leap.leaplaughlove.trading.quote;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("CurrentQuoteService Unit Tests")
class CurrentQuoteServiceTest {

    private static final long MAX_QUOTE_AGE_SECONDS = 5;

    @Mock
    private CurrentQuoteClient quoteClient;

    private CurrentQuoteService service;

    @BeforeEach
    void setUp() {
        service = new CurrentQuoteService(quoteClient, MAX_QUOTE_AGE_SECONDS);
    }

    private static QuoteSnapshot quoteAt(OffsetDateTime quoteTimestamp) {
        return new QuoteSnapshot("AAPL", new BigDecimal("150.20"), 100,
                new BigDecimal("150.30"), 100, new BigDecimal("150.25"), 100,
                "SIMULATED", quoteTimestamp);
    }

    @Test
    @DisplayName("returns the current quote when it is within the maximum quote age")
    void testReturnsFreshQuote() {
        QuoteSnapshot fresh = quoteAt(OffsetDateTime.now().minusSeconds(1));
        when(quoteClient.fetchLatest("AAPL")).thenReturn(Optional.of(fresh));

        QuoteSnapshot result = service.getCurrentQuote("AAPL");

        assertEquals(fresh, result);
    }

    @Test
    @DisplayName("rejects a quote older than the maximum quote age")
    void testRejectsStaleQuote() {
        when(quoteClient.fetchLatest("AAPL"))
                .thenReturn(Optional.of(quoteAt(OffsetDateTime.now().minusSeconds(MAX_QUOTE_AGE_SECONDS + 1))));

        assertThrows(StaleQuoteException.class, () -> service.getCurrentQuote("AAPL"));
    }

    @Test
    @DisplayName("rejects when the market data service holds no quote for the symbol")
    void testRejectsMissingQuote() {
        when(quoteClient.fetchLatest("AAPL")).thenReturn(Optional.empty());

        assertThrows(QuoteUnavailableException.class, () -> service.getCurrentQuote("AAPL"));
    }

    @Test
    @DisplayName("rejects a blank symbol")
    void testRejectsBlankSymbol() {
        assertThrows(IllegalArgumentException.class, () -> service.getCurrentQuote("  "));
    }
}
