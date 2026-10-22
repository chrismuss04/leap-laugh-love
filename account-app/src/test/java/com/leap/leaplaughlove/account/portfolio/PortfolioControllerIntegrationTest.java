package com.leap.leaplaughlove.account.portfolio;

import com.leap.leaplaughlove.common.security.JwtService;
import com.leap.leaplaughlove.account.quote.PriceHistoryClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@Sql(scripts = {"/db/positions_test_setup.sql", "/db/portfolio_history_test_setup.sql"})
@DisplayName("Portfolio History Integration Tests")
class PortfolioControllerIntegrationTest {

    private static final UUID CLIENT_OWNER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OWNER_ACCOUNT_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID OTHER_ACCOUNT_ID = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtService jwtService;

    @MockBean
    private PriceHistoryClient priceHistoryClient;

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

    @BeforeEach
    void setUp() {
        ownerToken = sessionToken(CLIENT_OWNER_ID, "owner@example.com");
        when(priceHistoryClient.fetchCloses(anyString(), any(), any(), anyInt())).thenReturn(List.of());
        when(priceHistoryClient.fetchLatestPrice("AAPL")).thenReturn(Optional.of(new BigDecimal("200")));
        when(priceHistoryClient.fetchLatestPrice("MSFT")).thenReturn(Optional.of(new BigDecimal("400")));
    }

    @Test
    @DisplayName("GET /api/account/portfolio/history values only the caller's accounts, before and after their trade")
    void valuesOwnPortfolio() throws Exception {
        // Now: 9,200 cash + 25 AAPL @ 200 + 10 MSFT @ 400 = 18,200.
        // A week ago, before the deposit and the 5-share buy: 20 AAPL @ 200 + 10 MSFT @ 400 = 8,000.
        // The 10,000 deposited since isn't a gain, so the change is the 200 the extra shares made.
        mockMvc.perform(get("/api/account/portfolio/history").param("range", "1W")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.range", is("1W")))
                .andExpect(jsonPath("$.intervalSeconds", is(3600)))
                .andExpect(jsonPath("$.startValue", is(8000.00)))
                .andExpect(jsonPath("$.endValue", is(18200.00)))
                .andExpect(jsonPath("$.netContributions", is(10000.00)))
                .andExpect(jsonPath("$.change", is(200.00)));
    }

    @Test
    @DisplayName("GET /api/account/portfolio/history?accountId= values one of the caller's accounts")
    void valuesOneAccount() throws Exception {
        mockMvc.perform(get("/api/account/portfolio/history").param("range", "1W")
                        .param("accountId", OWNER_ACCOUNT_ID.toString())
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.endValue", is(18200.00)))
                .andExpect(jsonPath("$.change", is(200.00)));
    }

    @Test
    @DisplayName("GET /api/account/portfolio/history?accountId= returns 404 for another client's account")
    void rejectsAnotherClientsAccount() throws Exception {
        mockMvc.perform(get("/api/account/portfolio/history").param("range", "1W")
                        .param("accountId", OTHER_ACCOUNT_ID.toString())
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET /api/account/portfolio/history?accountId= returns 400 for a malformed id")
    void rejectsMalformedAccountId() throws Exception {
        mockMvc.perform(get("/api/account/portfolio/history").param("range", "1W")
                        .param("accountId", "not-a-uuid")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("GET /api/account/portfolio/history returns 400 for an unknown range")
    void rejectsUnknownRange() throws Exception {
        mockMvc.perform(get("/api/account/portfolio/history").param("range", "10Y")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("GET /api/account/portfolio/history returns 401 without a token")
    void rejectsUnauthenticated() throws Exception {
        mockMvc.perform(get("/api/account/portfolio/history"))
                .andExpect(status().isUnauthorized());
    }
}

