package com.leap.leaplaughlove.trading.position;

import com.leap.leaplaughlove.iam.security.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@Sql(scripts = "/db/positions_test_setup.sql")
@DisplayName("Position Access Authorization Integration Tests")
class PositionControllerIntegrationTest {

    private static final String CLIENT_OWNER_ID = "11111111-1111-1111-1111-111111111111";
    private static final String CLIENT_OWNER_EMAIL = "owner@example.com";
    private static final String ACCOUNT_OWNER_USD_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String ACCOUNT_OTHER_USD_ID = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtService jwtService;

    private String ownerToken;

    @BeforeEach
    void setUp() {
        ownerToken = jwtService.generateToken(UUID.fromString(CLIENT_OWNER_ID), CLIENT_OWNER_EMAIL);
    }

    @Test
    @DisplayName("GET /api/trading/positions/accounts/{id} returns only positions for authenticated client's account")
    void testGetPositionsForOwnedAccount_Success() throws Exception {
        mockMvc.perform(get("/api/trading/positions/accounts/{accountId}", ACCOUNT_OWNER_USD_ID)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountId", is(ACCOUNT_OWNER_USD_ID)))
                .andExpect(jsonPath("$.positions", hasSize(2)))
                .andExpect(jsonPath("$.positions[0].symbol", is("AAPL")))
                .andExpect(jsonPath("$.positions[0].quantity", is(25)))
                .andExpect(jsonPath("$.positions[1].symbol", is("MSFT")))
                .andExpect(jsonPath("$.positions[1].quantity", is(10)));
    }

    @Test
    @DisplayName("GET /api/trading/positions/accounts/{id} returns 404 for unowned account")
    void testGetPositionsForUnownedAccount_NotFound() throws Exception {
        mockMvc.perform(get("/api/trading/positions/accounts/{accountId}", ACCOUNT_OTHER_USD_ID)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET /api/trading/positions/accounts/{id} returns 401 for unauthenticated request")
    void testGetPositions_Unauthenticated() throws Exception {
        mockMvc.perform(get("/api/trading/positions/accounts/{accountId}", ACCOUNT_OWNER_USD_ID))
                .andExpect(status().isUnauthorized());
    }
}
