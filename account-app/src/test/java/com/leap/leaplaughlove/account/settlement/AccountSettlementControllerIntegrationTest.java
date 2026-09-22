package com.leap.leaplaughlove.account.settlement;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leap.leaplaughlove.common.security.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@Sql(scripts = "/db/positions_test_setup.sql")
@DisplayName("AccountSettlementController Integration Tests")
class AccountSettlementControllerIntegrationTest {

    private static final String CLIENT_OWNER_ID = "11111111-1111-1111-1111-111111111111";
    private static final String CLIENT_OWNER_EMAIL = "owner@example.com";
    private static final String ACCOUNT_OWNER_USD_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String INSTRUMENT_AAPL_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaa0001";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JwtService jwtService;

    private String ownerToken;

    @BeforeEach
    void setUp() {
        ownerToken = jwtService.generateToken(UUID.fromString(CLIENT_OWNER_ID), CLIENT_OWNER_EMAIL);
    }

    @Test
    @DisplayName("GET /api/account/internal/accounts/{id}/validation-data — returns eligibility, balance and holding quantity")
    void testGetValidationData() throws Exception {
        mockMvc.perform(get("/api/account/internal/accounts/{accountId}/validation-data", ACCOUNT_OWNER_USD_ID)
                        .param("instrumentId", INSTRUMENT_AAPL_ID)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountActive", is(true)))
                .andExpect(jsonPath("$.tradingEnabled", is(true)))
                .andExpect(jsonPath("$.holdingQuantity", is(25)))
                .andExpect(jsonPath("$.baseCurrency", is("USD")))
                .andExpect(jsonPath("$.accountNumber", is("ACC-OWNER-USD")));
    }

    @Test
    @DisplayName("POST /api/account/internal/accounts/{id}/settlement — settles BUY order execution atomically")
    void testSettleBuyOrder() throws Exception {
        SettlementRequest request = new SettlementRequest(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.fromString(INSTRUMENT_AAPL_ID),
                "AAPL",
                "BUY",
                10,
                new BigDecimal("150.0000"),
                OffsetDateTime.now()
        );

        mockMvc.perform(post("/api/account/internal/accounts/{accountId}/settlement", ACCOUNT_OWNER_USD_ID)
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.positionQuantity", is(35)))
                .andExpect(jsonPath("$.cashLedgerId").isNotEmpty());
    }

    @Test
    @DisplayName("POST /api/account/internal/accounts/{id}/settlement — settles SELL order execution atomically")
    void testSettleSellOrder() throws Exception {
        SettlementRequest request = new SettlementRequest(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.fromString(INSTRUMENT_AAPL_ID),
                "AAPL",
                "SELL",
                10,
                new BigDecimal("160.0000"),
                OffsetDateTime.now()
        );

        mockMvc.perform(post("/api/account/internal/accounts/{accountId}/settlement", ACCOUNT_OWNER_USD_ID)
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.positionQuantity", is(15)))
                .andExpect(jsonPath("$.cashLedgerId").isNotEmpty());
    }
}

