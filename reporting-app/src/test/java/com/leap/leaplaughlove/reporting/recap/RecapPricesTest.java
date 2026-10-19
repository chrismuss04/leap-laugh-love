package com.leap.leaplaughlove.reporting.recap;

import com.leap.leaplaughlove.common.security.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

@DisplayName("RecapPrices")
class RecapPricesTest {

    private static final YearMonth SEP = YearMonth.of(2026, 9);
    private static final String HISTORY = "http://marketdata/api/marketdata/prices/%s/history";

    private MockRestServiceServer server;
    private JwtService jwtService;
    private RecapPrices prices;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://marketdata");
        server = MockRestServiceServer.bindTo(builder).ignoreExpectOrder(true).build();
        jwtService = mock(JwtService.class);
        when(jwtService.generateHistoryToken(any(UUID.class))).thenReturn("history-token");
        prices = new RecapPrices(new RecapPriceClient(builder.build()), jwtService);
    }

    private void expectHistory(String marketSymbol, String json) {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(HISTORY.formatted(marketSymbol))))
                .andExpect(header("Authorization", "Bearer history-token"))
                .andExpect(queryParam("interval", "86400"))
                .andExpect(queryParam("from", "2026-08-31T00:00:00Z"))
                .andExpect(queryParam("to", "2026-10-01T00:00:00Z"))
                .andRespond(withSuccess(json, MediaType.APPLICATION_JSON));
    }

    private static String page(String... bucketThenClose) {
        StringBuilder content = new StringBuilder();
        for (int i = 0; i < bucketThenClose.length; i += 2) {
            content.append(i == 0 ? "" : ",").append("{\"bucketStart\":\"").append(bucketThenClose[i])
                    .append("\",\"close\":").append(bucketThenClose[i + 1]).append('}');
        }
        return "{\"content\":[" + content + "],\"last\":true}";
    }

    @Test
    @DisplayName("fetches each symbol and the S&P 500 from the month before's last close, keyed by UTC day")
    void loadsDailyCloses() {
        expectHistory("AAPL", page("2026-08-31T00:00:00Z", "200", "2026-09-30T00:00:00Z", "220"));
        expectHistory("SPX", page("2026-08-31T00:00:00Z", "5000"));

        PriceBook book = prices.load(SEP, List.of("AAPL"));

        server.verify();
        assertEquals(Optional.of(new BigDecimal("200")), book.closeOn("AAPL", LocalDate.of(2026, 8, 31)));
        assertEquals(Optional.of(new BigDecimal("220")), book.closeOn("AAPL", LocalDate.of(2026, 9, 30)));
        assertEquals(Optional.of(new BigDecimal("5000")), book.closeOn("SPX", LocalDate.of(2026, 9, 30)));
    }

    @Test
    @DisplayName("asks for BTC/USD as BTCUSD but keeps it under its trading symbol")
    void mapsCryptoSymbol() {
        expectHistory("BTCUSD", page("2026-09-30T00:00:00Z", "65000"));
        expectHistory("SPX", page());

        PriceBook book = prices.load(SEP, List.of("BTC/USD"));

        server.verify();
        assertEquals(Optional.of(new BigDecimal("65000")), book.closeOn("BTC/USD", LocalDate.of(2026, 9, 30)));
        assertTrue(book.closeOn("SPX", LocalDate.of(2026, 9, 30)).isEmpty());
    }

    @Test
    @DisplayName("leaves a symbol market data doesn't know unpriced")
    void unknownSymbol() {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(HISTORY.formatted("ZZZZ"))))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));
        expectHistory("SPX", page("2026-09-30T00:00:00Z", "5000"));

        PriceBook book = prices.load(SEP, List.of("ZZZZ"));

        assertTrue(book.closeOn("ZZZZ", LocalDate.of(2026, 9, 30)).isEmpty());
    }

    @Test
    @DisplayName("fails when market data errors, rather than pricing from part of the closes")
    void outage() {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(HISTORY.formatted("AAPL"))))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        assertThrows(RecapPricesUnavailableException.class, () -> prices.load(SEP, List.of("AAPL")));
    }

    @Test
    @DisplayName("signs a fresh service token for each symbol, so a long batch never runs on an expired one")
    void tokenPerSymbol() {
        expectHistory("AAPL", page());
        expectHistory("MSFT", page());
        expectHistory("SPX", page());

        prices.load(SEP, List.of("AAPL", "MSFT"));

        verify(jwtService, times(3)).generateHistoryToken(new UUID(0L, 0L));
    }
}
