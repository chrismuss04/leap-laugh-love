package com.leap.leaplaughlove.trading.balance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leap.leaplaughlove.iam.security.JwtService;
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
import java.util.UUID;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@Sql(scripts = "/db/balance_transactions_test_setup.sql")
@DisplayName("Balance & Cash Movement Integration Tests (PB-06)")
class BalanceControllerIntegrationTest {

    private static final String CLIENT_OWNER_ID = "11111111-1111-1111-1111-111111111111";
    private static final String CLIENT_OWNER_EMAIL = "owner@example.com";

    private static final String ACCOUNT_OWNER_USD_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";

    private static final String CLIENT_OTHER_ID = "22222222-2222-2222-2222-222222222222";
    private static final String CLIENT_OTHER_EMAIL = "other@example.com";
    private static final String ACCOUNT_OTHER_USD_ID = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JwtService jwtService;

    private String ownerToken;

    @BeforeEach
    void setUp() {
        ownerToken = jwtService.generateToken(UUID.fromString(CLIENT_OWNER_ID), CLIENT_OWNER_EMAIL);
    }

    @Test
    @DisplayName("GET /api/trading/balance — returns balances for all active accounts owned by authenticated client")
    void testGetBalance_Success() throws Exception {
        mockMvc.perform(get("/api/trading/balance")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accounts", hasSize(1)))
                .andExpect(jsonPath("$.accounts[0].accountId", is(ACCOUNT_OWNER_USD_ID)))
                .andExpect(jsonPath("$.accounts[0].balance", is(100.00)))
                .andExpect(jsonPath("$.totalsByCurrency.USD", is(100.00)));
    }

    @Test
    @DisplayName("GET /api/trading/balance — unauthenticated request returns 401")
    void testGetBalance_Unauthenticated() throws Exception {
        mockMvc.perform(get("/api/trading/balance"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("POST /api/trading/balance/accounts/{id}/deposit — deposits funds into authorized account")
    void testDeposit_Success() throws Exception {
        var request = new CashMovementRequest(new BigDecimal("500.00"), "Wire transfer deposit");

        mockMvc.perform(post("/api/trading/balance/accounts/{accountId}/deposit", ACCOUNT_OWNER_USD_ID)
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountId", is(ACCOUNT_OWNER_USD_ID)))
                .andExpect(jsonPath("$.entryType", is("DEPOSIT")))
                .andExpect(jsonPath("$.amount", is(500.00)))
                .andExpect(jsonPath("$.currency", is("USD")))
                .andExpect(jsonPath("$.balanceAfter", is(600.00)))
                .andExpect(jsonPath("$.description", is("Wire transfer deposit")));
    }

    @Test
    @DisplayName("POST /api/trading/balance/accounts/{id}/deposit — forbidden when attempting to deposit into another client's account")
    void testDeposit_Forbidden() throws Exception {
        var request = new CashMovementRequest(new BigDecimal("100.00"), "Sneaky deposit");

        mockMvc.perform(post("/api/trading/balance/accounts/{accountId}/deposit", ACCOUNT_OTHER_USD_ID)
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("POST /api/trading/balance/accounts/{id}/withdrawal — withdraws funds when balance is sufficient")
    void testWithdrawal_Success() throws Exception {
        var request = new CashMovementRequest(new BigDecimal("50.00"), "ATM withdrawal");

        mockMvc.perform(post("/api/trading/balance/accounts/{accountId}/withdrawal", ACCOUNT_OWNER_USD_ID)
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountId", is(ACCOUNT_OWNER_USD_ID)))
                .andExpect(jsonPath("$.entryType", is("WITHDRAWAL")))
                .andExpect(jsonPath("$.amount", is(-50.00)))
                .andExpect(jsonPath("$.currency", is("USD")))
                .andExpect(jsonPath("$.balanceAfter", is(50.00)))
                .andExpect(jsonPath("$.description", is("ATM withdrawal")));
    }

    @Test
    @DisplayName("POST /api/trading/balance/accounts/{id}/withdrawal — rejects withdrawal when amount exceeds available balance")
    void testWithdrawal_InsufficientFunds() throws Exception {
        var request = new CashMovementRequest(new BigDecimal("99999.00"), "Overdraft attempt");

        mockMvc.perform(post("/api/trading/balance/accounts/{accountId}/withdrawal", ACCOUNT_OWNER_USD_ID)
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }
}
