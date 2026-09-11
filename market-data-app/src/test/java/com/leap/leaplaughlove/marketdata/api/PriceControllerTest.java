package com.leap.leaplaughlove.marketdata.api;

import com.leap.leaplaughlove.marketdata.history.PriceCandleRepository;
import com.leap.leaplaughlove.marketdata.simulation.MarketSimulationEngine;
import com.leap.leaplaughlove.marketdata.simulation.PriceState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PriceController.class)
class PriceControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private MarketSimulationEngine simulationEngine;

    @MockBean
    private PriceCandleRepository candleRepository;

    private static Authentication authenticatedClient() {
        UUID clientId = UUID.randomUUID();
        return new Authentication() {
            @Override
            public Collection<? extends GrantedAuthority> getAuthorities() {
                return List.of(new SimpleGrantedAuthority("ROLE_CLIENT"));
            }

            @Override
            public Object getCredentials() {
                return null;
            }

            @Override
            public Object getDetails() {
                return null;
            }

            @Override
            public Object getPrincipal() {
                return clientId;
            }

            @Override
            public boolean isAuthenticated() {
                return true;
            }

            @Override
            public void setAuthenticated(boolean isAuthenticated) {
            }

            @Override
            public String getName() {
                return clientId.toString();
            }
        };
    }

    @Test
    @DisplayName("GET /api/marketdata/prices - 200 with latest price for every active instrument")
    void testGetLatestPricesSuccess() throws Exception {
        OffsetDateTime asOf = OffsetDateTime.now();
        when(simulationEngine.latestAll()).thenReturn(List.of(
                new PriceState("AAPL", new BigDecimal("150.250000"), asOf)));

        mockMvc.perform(get("/api/marketdata/prices")
                        .with(csrf())
                        .with(authentication(authenticatedClient())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].symbol").value("AAPL"))
                .andExpect(jsonPath("$[0].price").value(150.25));
    }

    @Test
    @DisplayName("GET /api/marketdata/prices/{symbol} - 200 with the latest price")
    void testGetLatestPriceSuccess() throws Exception {
        OffsetDateTime asOf = OffsetDateTime.now();
        when(simulationEngine.latest("AAPL")).thenReturn(
                Optional.of(new PriceState("AAPL", new BigDecimal("150.250000"), asOf)));

        mockMvc.perform(get("/api/marketdata/prices/AAPL")
                        .with(csrf())
                        .with(authentication(authenticatedClient())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.symbol").value("AAPL"))
                .andExpect(jsonPath("$.price").value(150.25));
    }

    @Test
    @DisplayName("GET /api/marketdata/prices/{symbol} - 404 for an unknown symbol")
    void testGetLatestPriceUnknownSymbol() throws Exception {
        when(simulationEngine.latest("DOESNOTEXIST")).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/marketdata/prices/DOESNOTEXIST")
                        .with(csrf())
                        .with(authentication(authenticatedClient())))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithAnonymousUser
    @DisplayName("GET /api/marketdata/prices - 401 when unauthenticated")
    void testGetLatestPricesUnauthenticated() throws Exception {
        mockMvc.perform(get("/api/marketdata/prices"))
                .andExpect(status().isUnauthorized());
    }
}
