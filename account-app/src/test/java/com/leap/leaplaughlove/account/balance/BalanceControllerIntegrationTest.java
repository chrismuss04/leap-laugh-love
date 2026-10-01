package com.leap.leaplaughlove.account.balance;

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
import java.util.UUID;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@Sql(scripts = "/db/balance_transactions_test_setup.sql")
@DisplayName("Balance & Cash Movement Integration Tests")
class BalanceControllerIntegrationTest {

    private static final String CLIENT_OWNER_ID = "11111111-1111-1111-1111-111111111111";
    private static final String CLIENT_OWNER_EMAIL = "owner@example.com";

    private static final String ACCOUNT_OWNER_USD_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";

    private static final String ACCOUNT_OTHER_USD_ID = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JwtService jwtService;

    // Session Timeout & Revocation: exercise the real session validator with persisted test sessions.
    @Autowired private org.springframework.jdbc.core.JdbcTemplate sessionJdbc;

    private String sessionToken(UUID clientId, String email) {
        sessionJdbc.execute("CREATE SCHEMA IF NOT EXISTS iam");
        sessionJdbc.execute("CREATE TABLE IF NOT EXISTS iam.client_sessions (session_id UUID PRIMARY KEY, "
                + "client_id UUID NOT NULL, created_at TIMESTAMP WITH TIME ZONE NOT NULL, "
                + "last_activity_at TIMESTAMP WITH TIME ZONE NOT NULL, "
                + "expires_at TIMESTAMP WITH TIME ZONE NOT NULL, revoked_at TIMESTAMP WITH TIME ZONE)");
        UUID sid = UUID.randomUUID();
        var now = java.time.Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        var expiry = now.plusSeconds(3600);
        sessionJdbc.update("INSERT INTO iam.client_sessions VALUES (?, ?, ?, ?, ?, NULL)",
                sid, clientId, now.atOffset(java.time.ZoneOffset.UTC), now.atOffset(java.time.ZoneOffset.UTC),
                expiry.atOffset(java.time.ZoneOffset.UTC));
        return jwtService.generateToken(clientId, email, sid, now, expiry);
    }
    private String ownerToken;

    // Session Timeout & Revocation: account APIs enforce the same session state as IAM.
    @Test
    void revokedSessionCannotReadBalance() throws Exception {
        sessionJdbc.update("UPDATE iam.client_sessions SET revoked_at = CURRENT_TIMESTAMP");
        mockMvc.perform(get("/api/account/balance").header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isUnauthorized());
    }

    @BeforeEach
    void setUp() {
        ownerToken = sessionToken(UUID.fromString(CLIENT_OWNER_ID), CLIENT_OWNER_EMAIL);
    }

    @Test
    @DisplayName("GET /api/account/balance — returns balances for all active accounts owned by authenticated client")
    void testGetBalance_Success() throws Exception {
        mockMvc.perform(get("/api/account/balance")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accounts", hasSize(1)))
                .andExpect(jsonPath("$.accounts[0].accountId", is(ACCOUNT_OWNER_USD_ID)))
                .andExpect(jsonPath("$.accounts[0].balance", is(100.00)))
                .andExpect(jsonPath("$.totalByCurrency.USD", is(100.00)));
    }

    @Test
    @DisplayName("GET /api/account/balance — unauthenticated request returns 401")
    void testGetBalance_Unauthenticated() throws Exception {
        mockMvc.perform(get("/api/account/balance"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("POST /api/account/balance/accounts/{id}/deposit — deposits funds into authorized account")
    void testDeposit_Success() throws Exception {
        var request = new CashMovementRequest(new BigDecimal("500.00"), "Wire transfer deposit");

        mockMvc.perform(post("/api/account/balance/accounts/{accountId}/deposit", ACCOUNT_OWNER_USD_ID)
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
    @DisplayName("POST /api/account/balance/accounts/{id}/deposit — forbidden when attempting to deposit into another client's account")
    void testDeposit_Forbidden() throws Exception {
        var request = new CashMovementRequest(new BigDecimal("100.00"), "Sneaky deposit");

        mockMvc.perform(post("/api/account/balance/accounts/{accountId}/deposit", ACCOUNT_OTHER_USD_ID)
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("POST /api/account/balance/accounts/{id}/withdrawal — withdraws funds when balance is sufficient")
    void testWithdrawal_Success() throws Exception {
        var request = new CashMovementRequest(new BigDecimal("50.00"), "ATM withdrawal");

        mockMvc.perform(post("/api/account/balance/accounts/{accountId}/withdrawal", ACCOUNT_OWNER_USD_ID)
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
    @DisplayName("POST /api/account/balance/accounts/{id}/withdrawal — rejects withdrawal when amount exceeds available balance")
    void testWithdrawal_InsufficientFunds() throws Exception {
        var request = new CashMovementRequest(new BigDecimal("99999.00"), "Overdraft attempt");

        mockMvc.perform(post("/api/account/balance/accounts/{accountId}/withdrawal", ACCOUNT_OWNER_USD_ID)
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }
}

