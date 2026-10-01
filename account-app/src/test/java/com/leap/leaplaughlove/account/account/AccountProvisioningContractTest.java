package com.leap.leaplaughlove.account.account;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leap.leaplaughlove.common.security.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Plays iam-app's side of a client registration against the real account-app stack (security filter,
 * controllers, services, repositories). iam-app's own tests mock account-app and these are the
 * calls it makes, so this is the only place the two halves are checked against each other.
 * The JSON bodies below must stay identical to those in iam-app's AccountClientTest.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@Sql(scripts = "/db/balance_transactions_test_setup.sql")
@DisplayName("Account provisioning contract (iam-app registration flow)")
class AccountProvisioningContractTest {

    private static final UUID NEW_CLIENT_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final String NEW_CLIENT_EMAIL = "new.client@example.com";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JwtService jwtService;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void registerClientWithNoAccounts() {
        jdbc.update("INSERT INTO iam.clients (client_id, email, status) VALUES (?, ?, 'ACTIVE')",
                NEW_CLIENT_ID, NEW_CLIENT_EMAIL);
        jdbc.execute("CREATE TABLE IF NOT EXISTS iam.client_sessions (session_id UUID PRIMARY KEY, "
                + "client_id UUID NOT NULL, created_at TIMESTAMP WITH TIME ZONE NOT NULL, "
                + "last_activity_at TIMESTAMP WITH TIME ZONE NOT NULL, "
                + "expires_at TIMESTAMP WITH TIME ZONE NOT NULL, revoked_at TIMESTAMP WITH TIME ZONE)");
    }

    // Mirrors AccountProvisioningListener: a stored session, and a token carrying its id and expiry.
    private String provisioningToken() {
        UUID sessionId = UUID.randomUUID();
        Instant issuedAt = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        Instant expiresAt = issuedAt.plusSeconds(jwtService.getExpirationSeconds());
        jdbc.update("INSERT INTO iam.client_sessions "
                        + "(session_id, client_id, created_at, last_activity_at, expires_at) VALUES (?, ?, ?, ?, ?)",
                sessionId, NEW_CLIENT_ID, issuedAt.atOffset(ZoneOffset.UTC), issuedAt.atOffset(ZoneOffset.UTC),
                expiresAt.atOffset(ZoneOffset.UTC));
        return jwtService.generateToken(NEW_CLIENT_ID, NEW_CLIENT_EMAIL, sessionId, issuedAt, expiresAt);
    }

    @Test
    @DisplayName("opens the client's first account and books the initial deposit into it")
    void opensAccountThenBooksDeposit() throws Exception {
        String token = provisioningToken();

        String created = mockMvc.perform(post("/api/account/accounts")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"baseCurrency\":\"USD\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status", is("ACTIVE")))
                .andExpect(jsonPath("$.baseCurrency", is("USD")))
                .andExpect(jsonPath("$.tradingEnabled", is(true)))
                .andReturn().getResponse().getContentAsString();
        String accountId = objectMapper.readTree(created).get("accountId").asText();

        mockMvc.perform(post("/api/account/balance/accounts/{accountId}/deposit", accountId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":12500.75,\"description\":\"Initial deposit\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entryType", is("DEPOSIT")))
                .andExpect(jsonPath("$.amount", is(12500.75)))
                .andExpect(jsonPath("$.balanceAfter", is(12500.75)))
                .andExpect(jsonPath("$.description", is("Initial deposit")));

        mockMvc.perform(get("/api/account/accounts").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].accountId", is(accountId)));

        mockMvc.perform(get("/api/account/balance").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accounts", hasSize(1)))
                .andExpect(jsonPath("$.accounts[0].accountId", is(accountId)))
                .andExpect(jsonPath("$.accounts[0].currency", is("USD")))
                .andExpect(jsonPath("$.accounts[0].balance", is(12500.75)));
    }

    @Test
    @DisplayName("refuses a token that is not backed by a session, and opens no account")
    void tokenWithoutSessionIsRefused() throws Exception {
        String sessionlessToken = jwtService.generateToken(NEW_CLIENT_ID, NEW_CLIENT_EMAIL);

        mockMvc.perform(post("/api/account/accounts")
                        .header("Authorization", "Bearer " + sessionlessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"baseCurrency\":\"USD\"}"))
                .andExpect(status().isUnauthorized());

        Integer accounts = jdbc.queryForObject(
                "SELECT COUNT(*) FROM trading.accounts WHERE client_id = ?", Integer.class, NEW_CLIENT_ID);
        assertEquals(0, accounts);
    }
}
