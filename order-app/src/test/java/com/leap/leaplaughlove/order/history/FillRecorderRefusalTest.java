package com.leap.leaplaughlove.order.history;

import com.leap.leaplaughlove.order.client.AccountClient;
import com.leap.leaplaughlove.order.client.SettlementRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.ResourceAccessException;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

/**
 * Classifies settlement failures as account-app's real RestClient raises them, so the refusal
 * rules in FillRecorder hold for the responses account-app actually sends.
 */
@DisplayName("FillRecorder settlement failure classification")
class FillRecorderRefusalTest {

    private static final UUID ACCOUNT_ID = UUID.randomUUID();

    private MockRestServiceServer accountApp;
    private AccountClient accountClient;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://account-app");
        accountApp = MockRestServiceServer.bindTo(builder).build();
        accountClient = new AccountClient(builder.build());
    }

    private RuntimeException settleFailure() {
        return assertThrows(RuntimeException.class, () -> accountClient.settleOrderAs(ACCOUNT_ID,
                new SettlementRequest(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "AAPL", "SELL",
                        5, new BigDecimal("150.00"), OffsetDateTime.now()), "token"));
    }

    @Test
    @DisplayName("a 400 from account-app is a refusal, with account-app's own reason")
    void badRequestIsRefusalWithReason() {
        accountApp.expect(requestTo("http://account-app/api/account/internal/accounts/" + ACCOUNT_ID + "/settlement"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":\"BAD_REQUEST\",\"message\":\"Cannot settle sell: insufficient position quantity\"}"));

        RuntimeException failure = settleFailure();

        assertTrue(FillRecorder.isRefused(failure));
        assertEquals("Cannot settle sell: insufficient position quantity", FillRecorder.refusalReason(failure));
    }

    @Test
    @DisplayName("a 500 from account-app is not a refusal: it may have booked the fill")
    void serverErrorIsNotRefusal() {
        accountApp.expect(requestTo("http://account-app/api/account/internal/accounts/" + ACCOUNT_ID + "/settlement"))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        assertFalse(FillRecorder.isRefused(settleFailure()));
    }

    @Test
    @DisplayName("no answer from account-app is not a refusal")
    void noAnswerIsNotRefusal() {
        accountApp.expect(requestTo("http://account-app/api/account/internal/accounts/" + ACCOUNT_ID + "/settlement"))
                .andRespond(withException(new IOException("Read timed out")));

        RuntimeException failure = settleFailure();

        assertTrue(failure instanceof ResourceAccessException);
        assertFalse(FillRecorder.isRefused(failure));
    }
}
