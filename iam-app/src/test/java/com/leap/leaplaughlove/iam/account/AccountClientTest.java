package com.leap.leaplaughlove.iam.account;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

@DisplayName("iam AccountClient Unit Tests")
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

    @Test
    @DisplayName("createAccount posts the currency with the client's bearer token and returns the new account id")
    void createAccount() {
        UUID accountId = UUID.randomUUID();
        mockServer.expect(requestTo(BASE_URL + "/api/account/accounts"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer client-token"))
                .andExpect(content().json("{\"baseCurrency\":\"USD\"}"))
                .andRespond(withStatus(HttpStatus.CREATED).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"accountId\":\"" + accountId + "\",\"accountNumber\":\"ACC-AB12CD34\","
                                + "\"status\":\"ACTIVE\",\"baseCurrency\":\"USD\",\"tradingEnabled\":true}"));

        assertEquals(accountId, client.createAccount("client-token", "USD"));
        mockServer.verify();
    }

    @Test
    @DisplayName("createAccount surfaces a 4xx from account-app")
    void createAccountRejected() {
        mockServer.expect(requestTo(BASE_URL + "/api/account/accounts"))
                .andRespond(withStatus(HttpStatus.FORBIDDEN));

        assertThrows(HttpClientErrorException.class, () -> client.createAccount("client-token", "USD"));
    }

    @Test
    @DisplayName("createAccount fails when account-app answers without an account id")
    void createAccountWithoutId() {
        mockServer.expect(requestTo(BASE_URL + "/api/account/accounts"))
                .andRespond(withStatus(HttpStatus.CREATED));

        assertThrows(IllegalStateException.class, () -> client.createAccount("client-token", "USD"));
    }

    @Test
    @DisplayName("deposit posts the amount and description to the account's deposit endpoint")
    void deposit() {
        UUID accountId = UUID.randomUUID();
        mockServer.expect(requestTo(BASE_URL + "/api/account/balance/accounts/" + accountId + "/deposit"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer client-token"))
                .andExpect(content().json("{\"amount\":5000.00,\"description\":\"Initial deposit\"}"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        client.deposit("client-token", accountId, new BigDecimal("5000.00"), "Initial deposit");

        mockServer.verify();
    }
}
