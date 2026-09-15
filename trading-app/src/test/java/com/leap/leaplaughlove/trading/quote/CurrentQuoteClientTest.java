package com.leap.leaplaughlove.trading.quote;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

@DisplayName("CurrentQuoteClient Unit Tests")
class CurrentQuoteClientTest {

    private static final String BASE_URL = "http://market-data.test";
    private static final String QUOTE_URL = BASE_URL + "/api/marketdata/quotes/AAPL";

    private MockRestServiceServer mockServer;
    private CurrentQuoteClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        mockServer = MockRestServiceServer.bindTo(builder).build();
        client = new CurrentQuoteClient(builder.build());
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    private static void givenCallerToken(String token) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(HttpHeaders.AUTHORIZATION, token);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    @Test
    @DisplayName("fetches the latest quote and forwards the caller's bearer token")
    void testFetchesLatestQuote() {
        givenCallerToken("Bearer caller-token");
        OffsetDateTime quoteTimestamp = OffsetDateTime.now();
        mockServer.expect(requestTo(QUOTE_URL))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer caller-token"))
                .andRespond(withSuccess("""
                        {"symbol":"AAPL","bidPrice":150.20,"bidSize":100,"askPrice":150.30,\
                        "askSize":100,"lastPrice":150.25,"lastSize":100,"exchange":"SIMULATED",\
                        "quoteTimestamp":"%s"}""".formatted(quoteTimestamp),
                        MediaType.APPLICATION_JSON));

        Optional<QuoteSnapshot> result = client.fetchLatest("AAPL");

        mockServer.verify();
        assertTrue(result.isPresent());
        assertEquals("AAPL", result.get().symbol());
        assertEquals(0, new BigDecimal("150.30").compareTo(result.get().askPrice()));
        assertEquals(quoteTimestamp.toInstant(), result.get().quoteTimestamp().toInstant());
    }

    @Test
    @DisplayName("returns empty when the market data service holds no quote for the symbol")
    void testReturnsEmptyWhenNotFound() {
        mockServer.expect(requestTo(QUOTE_URL)).andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertTrue(client.fetchLatest("AAPL").isEmpty());
        mockServer.verify();
    }

    @Test
    @DisplayName("rejects when the market data service returns an error")
    void testRejectsWhenMarketDataFails() {
        mockServer.expect(requestTo(QUOTE_URL)).andRespond(withServerError());

        assertThrows(QuoteUnavailableException.class, () -> client.fetchLatest("AAPL"));
        mockServer.verify();
    }
}
