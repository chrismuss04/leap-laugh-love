package com.leap.leaplaughlove.trading.portfolio;

import com.leap.leaplaughlove.common.security.JwtService;
import com.leap.leaplaughlove.trading.quote.PriceHistoryClient;
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

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtService jwtService;

    @MockBean
    private PriceHistoryClient priceHistoryClient;

    private String ownerToken;

    @BeforeEach
    void setUp() {
        ownerToken = jwtService.generateToken(CLIENT_OWNER_ID, "owner@example.com");
        when(priceHistoryClient.fetchCloses(anyString(), any(), any(), anyInt())).thenReturn(List.of());
        when(priceHistoryClient.fetchLatestPrice("AAPL")).thenReturn(Optional.of(new BigDecimal("200")));
        when(priceHistoryClient.fetchLatestPrice("MSFT")).thenReturn(Optional.of(new BigDecimal("400")));
    }

    @Test
    @DisplayName("GET /api/trading/portfolio/history values only the caller's accounts, before and after their trade")
    void valuesOwnPortfolio() throws Exception {
        // Now: 9,200 cash + 25 AAPL @ 200 + 10 MSFT @ 400 = 18,200.
        // A day ago, before the 5-share buy: 10,000 cash + 20 AAPL @ 200 + 10 MSFT @ 400 = 18,000.
        mockMvc.perform(get("/api/trading/portfolio/history").param("range", "1D")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.range", is("1D")))
                .andExpect(jsonPath("$.intervalSeconds", is(300)))
                .andExpect(jsonPath("$.startValue", is(18000.00)))
                .andExpect(jsonPath("$.endValue", is(18200.00)))
                .andExpect(jsonPath("$.change", is(200.00)));
    }

    @Test
    @DisplayName("GET /api/trading/portfolio/history returns 400 for an unknown range")
    void rejectsUnknownRange() throws Exception {
        mockMvc.perform(get("/api/trading/portfolio/history").param("range", "10Y")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("GET /api/trading/portfolio/history returns 401 without a token")
    void rejectsUnauthenticated() throws Exception {
        mockMvc.perform(get("/api/trading/portfolio/history"))
                .andExpect(status().isUnauthorized());
    }
}
