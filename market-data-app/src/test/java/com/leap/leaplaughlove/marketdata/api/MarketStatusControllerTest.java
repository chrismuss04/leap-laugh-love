package com.leap.leaplaughlove.marketdata.api;

import com.leap.leaplaughlove.common.market.MarketCalendar;
import com.leap.leaplaughlove.common.market.MarketClock;
import com.leap.leaplaughlove.common.market.MarketSession;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(MarketStatusController.class)
class MarketStatusControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private MarketClock marketClock;

    private static Authentication client() {
        return new UsernamePasswordAuthenticationToken(UUID.randomUUID(), null,
                List.of(new SimpleGrantedAuthority("ROLE_CLIENT")));
    }

    private static Instant eastern(String time) {
        return LocalDateTime.parse(time).atZone(MarketCalendar.ZONE).toInstant();
    }

    @Test
    @DisplayName("GET /api/marketdata/market/status - 200 with the session, the next change and today's hours")
    void returnsTheStatus() throws Exception {
        // Wednesday 14 October 2026, 10:00 ET: regular trading until 16:00.
        Instant now = eastern("2026-10-14T10:00:00");
        when(marketClock.status()).thenReturn(
                new MarketClock(Clock.fixed(now, ZoneOffset.UTC), MarketClock.Mode.REAL, null, Duration.ZERO).status());

        mockMvc.perform(get("/api/marketdata/market/status").with(authentication(client())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.session").value("REGULAR"))
                .andExpect(jsonPath("$.nextSession").value("AFTER_HOURS"))
                .andExpect(jsonPath("$.nextTransitionAt").value("2026-10-14T20:00:00Z"))
                .andExpect(jsonPath("$.today.open").value(true))
                .andExpect(jsonPath("$.today.earlyClose").value(false))
                .andExpect(jsonPath("$.today.regularOpen").value("2026-10-14T13:30:00Z"))
                .andExpect(jsonPath("$.today.afterHoursClose").value("2026-10-15T00:00:00Z"))
                .andExpect(jsonPath("$.mode").value("REAL"))
                .andExpect(jsonPath("$.forced").value(false));
    }

    @Test
    @DisplayName("GET /api/marketdata/market/status - a holiday has no hours and names the holiday")
    void holiday() throws Exception {
        Instant now = eastern("2026-11-26T12:00:00");
        when(marketClock.status()).thenReturn(
                new MarketClock(Clock.fixed(now, ZoneOffset.UTC), MarketClock.Mode.REAL, null, Duration.ZERO).status());

        mockMvc.perform(get("/api/marketdata/market/status").with(authentication(client())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.session").value("CLOSED"))
                .andExpect(jsonPath("$.today.open").value(false))
                .andExpect(jsonPath("$.today.holiday").value("Thanksgiving Day"))
                .andExpect(jsonPath("$.today.regularOpen").doesNotExist())
                .andExpect(jsonPath("$.nextRegularOpen").value("2026-11-27T14:30:00Z"));
    }

    @Test
    @DisplayName("GET /api/marketdata/market/status - a pinned session never changes")
    void forcedSession() throws Exception {
        Instant now = eastern("2026-10-14T10:00:00");
        when(marketClock.status()).thenReturn(new MarketClock(Clock.fixed(now, ZoneOffset.UTC),
                MarketClock.Mode.REAL, MarketSession.AFTER_HOURS, Duration.ZERO).status());

        mockMvc.perform(get("/api/marketdata/market/status").with(authentication(client())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.session").value("AFTER_HOURS"))
                .andExpect(jsonPath("$.nextSession").doesNotExist())
                .andExpect(jsonPath("$.nextRegularOpen").doesNotExist())
                .andExpect(jsonPath("$.forced").value(true));
    }

    @Test
    @WithAnonymousUser
    @DisplayName("GET /api/marketdata/market/status - 401 when unauthenticated")
    void unauthenticated() throws Exception {
        mockMvc.perform(get("/api/marketdata/market/status"))
                .andExpect(status().isUnauthorized());
    }
}
