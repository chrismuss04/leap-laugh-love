package com.leap.leaplaughlove.account.quote;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.hamcrest.Matchers.startsWith;

@DisplayName("PriceHistoryClient Unit Tests")
class PriceHistoryClientTest {

    private static final String BASE_URL = "http://market-data.test";

    private MockRestServiceServer mockServer;
    private PriceHistoryClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        mockServer = MockRestServiceServer.bindTo(builder).build();
        client = new PriceHistoryClient(builder.build());
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer caller-token");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    @DisplayName("pages through history, sends UTC bounds, and returns closes oldest first")
    void fetchesAllPagesOldestFirst() {
        OffsetDateTime from = OffsetDateTime.parse("2026-01-01T05:00:00+05:00");
        OffsetDateTime to = OffsetDateTime.parse("2026-01-02T00:00:00.123Z");
        mockServer.expect(requestTo(startsWith(BASE_URL + "/api/marketdata/prices/AAPL/history")))
                .andExpect(queryParam("from", "2026-01-01T00:00:00Z"))
                .andExpect(queryParam("to", "2026-01-02T00:00:00Z"))
                .andExpect(queryParam("interval", "3600"))
                .andExpect(queryParam("page", "0"))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer caller-token"))
                .andRespond(withSuccess("""
                        {"content":[{"bucketStart":"2026-01-01T02:00:00Z","close":102},
                                    {"bucketStart":"2026-01-01T01:00:00Z","close":101}],"last":false}""",
                        MediaType.APPLICATION_JSON));
        mockServer.expect(requestTo(startsWith(BASE_URL + "/api/marketdata/prices/AAPL/history")))
                .andExpect(queryParam("page", "1"))
                .andRespond(withSuccess("""
                        {"content":[{"bucketStart":"2026-01-01T00:00:00Z","close":100}],"last":true}""",
                        MediaType.APPLICATION_JSON));

        List<PriceHistoryClient.CandleClose> closes = client.fetchCloses("AAPL", from, to, 3600);

        mockServer.verify();
        assertEquals(List.of(new BigDecimal("100"), new BigDecimal("101"), new BigDecimal("102")),
                closes.stream().map(PriceHistoryClient.CandleClose::close).toList());
    }

    @Test
    @DisplayName("maps a market data failure to QuoteUnavailableException")
    void failureIsUnavailable() {
        mockServer.expect(requestTo(startsWith(BASE_URL + "/api/marketdata/prices/AAPL/history")))
                .andRespond(withServerError());

        OffsetDateTime now = OffsetDateTime.now();
        assertThrows(QuoteUnavailableException.class,
                () -> client.fetchCloses("AAPL", now.minusDays(1), now, 300));
    }

    @Test
    @DisplayName("returns the latest price, or empty for an unknown symbol")
    void fetchesLatestPrice() {
        mockServer.expect(requestTo(BASE_URL + "/api/marketdata/prices/AAPL"))
                .andRespond(withSuccess("""
                        {"symbol":"AAPL","price":187.25,"asOf":"2026-01-01T00:00:00Z"}""",
                        MediaType.APPLICATION_JSON));
        mockServer.expect(requestTo(BASE_URL + "/api/marketdata/prices/NOPE"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertEquals(Optional.of(new BigDecimal("187.25")), client.fetchLatestPrice("AAPL"));
        assertTrue(client.fetchLatestPrice("NOPE").isEmpty());
    }
}

