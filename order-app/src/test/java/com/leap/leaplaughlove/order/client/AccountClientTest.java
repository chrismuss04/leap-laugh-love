package com.leap.leaplaughlove.order.client;

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
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

@DisplayName("AccountClient Unit Tests")
class AccountClientTest {

    private static final String BASE_URL = "http://account-app.test";

    private MockRestServiceServer mockServer;
    private AccountClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        mockServer = MockRestServiceServer.bindTo(builder).build();
        client = new AccountClient(builder.build());
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    private void givenCallerToken(String token) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(HttpHeaders.AUTHORIZATION, token);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    @Test
    @DisplayName("getValidationData forwards token, sends query param, and parses response")
    void testGetValidationDataSuccess() {
        givenCallerToken("Bearer test-token");
        UUID accountId = UUID.randomUUID();
        UUID instrumentId = UUID.randomUUID();

        String expectedUri = BASE_URL + "/api/account/internal/accounts/" + accountId + "/validation-data?instrumentId=" + instrumentId;
        mockServer.expect(requestTo(expectedUri))
                .andExpect(method(HttpMethod.GET))
                .andExpect(queryParam("instrumentId", instrumentId.toString()))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer test-token"))
                .andRespond(withSuccess("""
                        {
                            "accountActive": true,
                            "tradingEnabled": true,
                            "cashBalance": 10500.50,
                            "holdingQuantity": 25,
                            "baseCurrency": "USD",
                            "accountNumber": "ACC-12345"
                        }""", MediaType.APPLICATION_JSON));

        AccountValidationDto dto = client.getValidationData(accountId, instrumentId);

        mockServer.verify();
        assertNotNull(dto);
        assertTrue(dto.accountActive());
        assertTrue(dto.tradingEnabled());
        assertEquals(new BigDecimal("10500.50"), dto.cashBalance());
        assertEquals(25L, dto.holdingQuantity());
        assertEquals("USD", dto.baseCurrency());
        assertEquals("ACC-12345", dto.accountNumber());
    }

    @Test
    @DisplayName("getValidationData without RequestContext sends request without Authorization header")
    void testGetValidationDataWithoutRequestContext() {
        UUID accountId = UUID.randomUUID();
        UUID instrumentId = UUID.randomUUID();

        String expectedUri = BASE_URL + "/api/account/internal/accounts/" + accountId + "/validation-data?instrumentId=" + instrumentId;
        mockServer.expect(requestTo(expectedUri))
                .andExpect(method(HttpMethod.GET))
                .andExpect(headerDoesNotExist(HttpHeaders.AUTHORIZATION))
                .andRespond(withSuccess("""
                        {
                            "accountActive": false,
                            "tradingEnabled": false,
                            "cashBalance": 0,
                            "holdingQuantity": 0,
                            "baseCurrency": "USD",
                            "accountNumber": "ACC-00000"
                        }""", MediaType.APPLICATION_JSON));

        AccountValidationDto dto = client.getValidationData(accountId, instrumentId);

        mockServer.verify();
        assertFalse(dto.accountActive());
    }

    @Test
    @DisplayName("settleOrder posts settlement request with forwarded token and parses response")
    void testSettleOrderSuccess() {
        givenCallerToken("Bearer test-token-2");
        UUID accountId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        UUID executionId = UUID.randomUUID();
        UUID instrumentId = UUID.randomUUID();
        UUID cashLedgerId = UUID.randomUUID();
        OffsetDateTime executedAt = OffsetDateTime.now();

        SettlementRequest request = new SettlementRequest(
                orderId, executionId, instrumentId, "AAPL", "BUY", 10L, new BigDecimal("150.00"), executedAt);

        String expectedUri = BASE_URL + "/api/account/internal/accounts/" + accountId + "/settlement";
        mockServer.expect(requestTo(expectedUri))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer test-token-2"))
                .andExpect(content().string(containsString("\"symbol\":\"AAPL\"")))
                .andExpect(content().string(containsString("\"side\":\"BUY\"")))
                .andExpect(content().string(containsString("\"quantity\":10")))
                .andRespond(withSuccess("""
                        {
                            "cashLedgerId": "%s",
                            "balanceAfter": 8500.00,
                            "positionQuantity": 10,
                            "positionAvgCost": 150.00
                        }""".formatted(cashLedgerId), MediaType.APPLICATION_JSON));

        SettlementResponse response = client.settleOrder(accountId, request);

        mockServer.verify();
        assertNotNull(response);
        assertEquals(cashLedgerId, response.cashLedgerId());
        assertEquals(new BigDecimal("8500.00"), response.balanceAfter());
        assertEquals(10L, response.positionQuantity());
        assertEquals(new BigDecimal("150.00"), response.positionAvgCost());
    }

    @Test
    @DisplayName("settleOrderAs posts settlement request using explicit bearer token")
    void testSettleOrderAsSuccess() {
        UUID accountId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        UUID executionId = UUID.randomUUID();
        UUID instrumentId = UUID.randomUUID();
        UUID cashLedgerId = UUID.randomUUID();
        OffsetDateTime executedAt = OffsetDateTime.now();
        String explicitToken = "daemon-seeded-fill-jwt";

        SettlementRequest request = new SettlementRequest(
                orderId, executionId, instrumentId, "MSFT", "SELL", 5L, new BigDecimal("320.00"), executedAt);

        String expectedUri = BASE_URL + "/api/account/internal/accounts/" + accountId + "/settlement";
        mockServer.expect(requestTo(expectedUri))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + explicitToken))
                .andRespond(withSuccess("""
                        {
                            "cashLedgerId": "%s",
                            "balanceAfter": 11600.00,
                            "positionQuantity": 0,
                            "positionAvgCost": 0.00
                        }""".formatted(cashLedgerId), MediaType.APPLICATION_JSON));

        SettlementResponse response = client.settleOrderAs(accountId, request, explicitToken);

        mockServer.verify();
        assertNotNull(response);
        assertEquals(cashLedgerId, response.cashLedgerId());
        assertEquals(new BigDecimal("11600.00"), response.balanceAfter());
        assertEquals(0L, response.positionQuantity());
    }

    @Test
    @DisplayName("getAccountIdsForClient retrieves and maps account summaries to UUID list")
    void testGetAccountIdsForClient() {
        givenCallerToken("Bearer client-jwt");
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();

        mockServer.expect(requestTo(BASE_URL + "/api/account/accounts"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer client-jwt"))
                .andRespond(withSuccess("""
                        [
                            {"accountId":"%s","accountNumber":"ACC-1","status":"ACTIVE","baseCurrency":"USD","tradingEnabled":true,"createdAt":"2026-01-01T00:00:00Z"},
                            {"accountId":"%s","accountNumber":"ACC-2","status":"ACTIVE","baseCurrency":"USD","tradingEnabled":false,"createdAt":"2026-01-02T00:00:00Z"}
                        ]""".formatted(id1, id2), MediaType.APPLICATION_JSON));

        List<UUID> accountIds = client.getAccountIdsForClient();

        mockServer.verify();
        assertEquals(List.of(id1, id2), accountIds);
    }

    @Test
    @DisplayName("getValidationData throws NotFound when account service responds with 404")
    void testGetValidationData404ThrowsNotFound() {
        UUID accountId = UUID.randomUUID();
        UUID instrumentId = UUID.randomUUID();

        mockServer.expect(requestTo(containsString("/validation-data")))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThrows(HttpClientErrorException.NotFound.class,
                () -> client.getValidationData(accountId, instrumentId));
        mockServer.verify();
    }

    @Test
    @DisplayName("settleOrder throws BadRequest when account service responds with 400")
    void testSettleOrder400ThrowsBadRequest() {
        UUID accountId = UUID.randomUUID();
        SettlementRequest req = new SettlementRequest(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "AAPL", "BUY", 1L, BigDecimal.ONE, OffsetDateTime.now());

        mockServer.expect(requestTo(containsString("/settlement")))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST));

        assertThrows(HttpClientErrorException.BadRequest.class,
                () -> client.settleOrder(accountId, req));
        mockServer.verify();
    }

    @Test
    @DisplayName("getAccountIdsForClient throws InternalServerError when account service responds with 500")
    void testGetAccountIdsForClient500ThrowsServerError() {
        mockServer.expect(requestTo(BASE_URL + "/api/account/accounts"))
                .andRespond(withServerError());

        assertThrows(HttpServerErrorException.InternalServerError.class,
                () -> client.getAccountIdsForClient());
        mockServer.verify();
    }

    @Test
    @DisplayName("getAccountIdsForClient returns empty list when response body is null")
    void testGetAccountIdsForClientNullBodyReturnsEmptyList() {
        mockServer.expect(requestTo(BASE_URL + "/api/account/accounts"))
                .andRespond(withStatus(HttpStatus.NO_CONTENT));

        List<UUID> result = client.getAccountIdsForClient();
        assertTrue(result.isEmpty());
        mockServer.verify();
    }
}
