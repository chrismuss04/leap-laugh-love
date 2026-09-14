package com.leap.leaplaughlove.marketdata.api;

import com.leap.leaplaughlove.marketdata.ingestion.QuoteIngestionService;
import com.leap.leaplaughlove.marketdata.ingestion.QuoteState;
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

@WebMvcTest(QuoteController.class)
class QuoteControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private QuoteIngestionService quoteIngestionService;

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

    private static QuoteState sampleQuote(OffsetDateTime quoteTimestamp) {
        return new QuoteState("AAPL", new BigDecimal("150.200000"), 100,
                new BigDecimal("150.300000"), 100, new BigDecimal("150.250000"), 100,
                "SIMULATED", 1, quoteTimestamp, OffsetDateTime.now());
    }

    @Test
    @DisplayName("GET /api/marketdata/quotes - 200 with the latest quote for every symbol")
    void testGetLatestQuotesSuccess() throws Exception {
        when(quoteIngestionService.latestAll()).thenReturn(List.of(sampleQuote(OffsetDateTime.now())));

        mockMvc.perform(get("/api/marketdata/quotes")
                        .with(csrf())
                        .with(authentication(authenticatedClient())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].symbol").value("AAPL"))
                .andExpect(jsonPath("$[0].bidPrice").value(150.20))
                .andExpect(jsonPath("$[0].askPrice").value(150.30))
                .andExpect(jsonPath("$[0].lastPrice").value(150.25));
    }

    @Test
    @DisplayName("GET /api/marketdata/quotes/{symbol} - 200 with the latest quote")
    void testGetLatestQuoteSuccess() throws Exception {
        when(quoteIngestionService.latest("AAPL")).thenReturn(Optional.of(sampleQuote(OffsetDateTime.now())));

        mockMvc.perform(get("/api/marketdata/quotes/AAPL")
                        .with(csrf())
                        .with(authentication(authenticatedClient())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.symbol").value("AAPL"))
                .andExpect(jsonPath("$.bidSize").value(100));
    }

    @Test
    @DisplayName("GET /api/marketdata/quotes/{symbol} - 404 for an unknown symbol")
    void testGetLatestQuoteUnknownSymbol() throws Exception {
        when(quoteIngestionService.latest("DOESNOTEXIST")).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/marketdata/quotes/DOESNOTEXIST")
                        .with(csrf())
                        .with(authentication(authenticatedClient())))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithAnonymousUser
    @DisplayName("GET /api/marketdata/quotes - 401 when unauthenticated")
    void testGetLatestQuotesUnauthenticated() throws Exception {
        mockMvc.perform(get("/api/marketdata/quotes"))
                .andExpect(status().isUnauthorized());
    }
}
