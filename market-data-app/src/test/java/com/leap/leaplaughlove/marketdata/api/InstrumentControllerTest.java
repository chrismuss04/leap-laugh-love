package com.leap.leaplaughlove.marketdata.api;

import com.leap.leaplaughlove.common.security.ClientSessionValidator;
import com.leap.leaplaughlove.common.security.JwtService;
import com.leap.leaplaughlove.marketdata.instrument.InstrumentOverviewService;
import com.leap.leaplaughlove.marketdata.security.MarketDataSecurityConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;
import java.time.OffsetDateTime;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(InstrumentController.class)
@Import(MarketDataSecurityConfig.class)
class InstrumentControllerTest {
    @Autowired private MockMvc mvc;
    @MockBean private InstrumentOverviewService service;
    @MockBean private JwtService jwt;
    @MockBean private ClientSessionValidator sessions;

    @Test
    @WithMockUser(roles = "CLIENT")
    void defaultsToToday() throws Exception {
        var now = OffsetDateTime.parse("2026-10-12T00:00:00Z");
        when(service.getOverview("today", 0, 10, "", "", "name", "ascending"))
                .thenReturn(new InstrumentOverviewResponse("today", now, now, List.of()));
        mvc.perform(get("/api/marketdata/instruments"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.period").value("today"))
                .andExpect(jsonPath("$.instruments").isArray());
        verify(service).getOverview("today", 0, 10, "", "", "name", "ascending");
    }

    @ParameterizedTest
    @ValueSource(strings = {"week", "month"})
    @WithMockUser(roles = "CLIENT")
    void passesSelectedPeriod(String period) throws Exception {
        mvc.perform(get("/api/marketdata/instruments").param("period", period)).andExpect(status().isOk());
        verify(service).getOverview(period, 0, 10, "", "", "name", "ascending");
    }

    @Test
    @WithMockUser(roles = "CLIENT")
    void invalidPeriodReturnsBadRequest() throws Exception {
        when(service.getOverview("year", 0, 10, "", "", "name", "ascending"))
                .thenThrow(new ResponseStatusException(HttpStatus.BAD_REQUEST));
        mvc.perform(get("/api/marketdata/instruments").param("period", "year"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(roles = "CLIENT")
    void passesPaginationAndSearchOptions() throws Exception {
        mvc.perform(get("/api/marketdata/instruments").param("period", "week")
                        .param("page", "2").param("size", "20").param("query", "apple")
                        .param("asset", "EQUITY").param("sort", "price").param("direction", "descending"))
                .andExpect(status().isOk());
        verify(service).getOverview("week", 2, 20, "apple", "EQUITY", "price", "descending");
    }

    @Test
    void anonymousCannotReadOverview() throws Exception {
        mvc.perform(get("/api/marketdata/instruments")).andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(strings = {"COMMERCIAL_ANALYST", "TRADING_OPERATIONS", "SERVICE"})
    void nonClientRolesCannotReadOverview(String role) throws Exception {
        mvc.perform(get("/api/marketdata/instruments").with(
                org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user("staff").roles(role)))
                .andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }
}
