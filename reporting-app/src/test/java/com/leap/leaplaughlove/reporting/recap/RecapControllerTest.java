package com.leap.leaplaughlove.reporting.recap;

import com.leap.leaplaughlove.common.security.ClientSessionValidator;
import com.leap.leaplaughlove.common.security.JwtService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(RecapController.class)
@DisplayName("RecapController")
class RecapControllerTest {

    private static final String LATEST = "/api/reporting/recaps/latest";
    private static final YearMonth SEP = YearMonth.of(2026, 9);
    private static final UUID CLIENT = UUID.randomUUID();

    @Autowired private MockMvc mockMvc;
    @MockBean private JwtService jwtService;
    @MockBean private ClientSessionValidator sessions;
    @MockBean private RecapService recaps;

    private static Authentication client() {
        return new UsernamePasswordAuthenticationToken(CLIENT, null, List.of(new SimpleGrantedAuthority("ROLE_CLIENT")));
    }

    @Test
    @DisplayName("GET /latest - the signed-in client's recap, months as yyyy-MM")
    void latest() throws Exception {
        RecapPayload payload = new RecapCalculator().calculate(SEP, List.of(), PriceBook.empty(), Map.of());
        when(recaps.latest(CLIENT)).thenReturn(
                new RecapResponse(SEP, OffsetDateTime.parse("2026-10-01T00:30:00Z"), true, payload.withTopPercent(4)));

        mockMvc.perform(get(LATEST).with(authentication(client())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.month").value("2026-09"))
                .andExpect(jsonPath("$.ranked").value(true))
                .andExpect(jsonPath("$.recap.month").value("2026-09"))
                .andExpect(jsonPath("$.recap.quiet").value(true))
                .andExpect(jsonPath("$.recap.moneyMoved.marketDays").value(22))
                .andExpect(jsonPath("$.recap.topPercent").value(4))
                .andExpect(jsonPath("$.recap.tradingReturn.dailyPnl.length()").value(30));
    }

    @Test
    @DisplayName("GET /latest - 503 with the shared error body when prices are unavailable")
    void pricesUnavailable() throws Exception {
        when(recaps.latest(CLIENT)).thenThrow(new RecapPricesUnavailableException("down", null));

        mockMvc.perform(get(LATEST).with(authentication(client())))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("RECAP_UNAVAILABLE"))
                .andExpect(jsonPath("$.message").isNotEmpty());
    }
}
