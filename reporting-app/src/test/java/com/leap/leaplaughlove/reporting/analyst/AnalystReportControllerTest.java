package com.leap.leaplaughlove.reporting.analyst;

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

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AnalystReportController.class)
@DisplayName("AnalystReportController")
class AnalystReportControllerTest {

    private static final ReportWindow WINDOW = new ReportWindow(YearMonth.of(2025, 10), YearMonth.of(2026, 9));
    private static final YearMonth SEP = YearMonth.of(2026, 9);

    @Autowired private MockMvc mockMvc;
    @MockBean private JwtService jwtService;
    @MockBean private ClientSessionValidator sessions;
    @MockBean private AnalystReportService reports;

    private static Authentication analyst() {
        return new UsernamePasswordAuthenticationToken(
                UUID.randomUUID(), null, List.of(new SimpleGrantedAuthority("ROLE_COMMERCIAL_ANALYST")));
    }

    @Test
    @DisplayName("GET /scorecard - months as yyyy-MM, nulls where there is nothing to compare")
    void scorecard() throws Exception {
        var metric = new ScorecardReport.Metric(new BigDecimal("1200.00"), new BigDecimal("50.0"), null);
        when(reports.getScorecard()).thenReturn(new ScorecardReport(WINDOW,
                List.of(new ScorecardReport.Month(SEP, metric, metric, metric, metric, metric))));

        mockMvc.perform(get("/api/reporting/analyst/scorecard").with(authentication(analyst())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.window.firstMonth").value("2025-10"))
                .andExpect(jsonPath("$.window.lastMonth").value("2026-09"))
                .andExpect(jsonPath("$.window.startDate").doesNotExist())
                .andExpect(jsonPath("$.months[0].month").value("2026-09"))
                .andExpect(jsonPath("$.months[0].volume.value").value(1200.00))
                .andExpect(jsonPath("$.months[0].volume.monthOverMonthPct").value(50.0))
                .andExpect(jsonPath("$.months[0].volume.yearOverYearPct").isEmpty());
    }

    @Test
    @DisplayName("GET /client-activity")
    void clientActivity() throws Exception {
        when(reports.getClientActivity()).thenReturn(new ClientActivityReport(WINDOW,
                List.of(new ClientActivityReport.Month(SEP, 15, 3, 10, 2, 4))));

        mockMvc.perform(get("/api/reporting/analyst/client-activity").with(authentication(analyst())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.months[0].activeClients").value(15))
                .andExpect(jsonPath("$.months[0].reactivatedClients").value(2))
                .andExpect(jsonPath("$.months[0].lapsedClients").value(4));
    }

    @Test
    @DisplayName("GET /retention")
    void retention() throws Exception {
        when(reports.getRetention()).thenReturn(new RetentionReport(WINDOW, List.of(new RetentionReport.Cohort(SEP, 4,
                List.of(new RetentionReport.Cell(0, SEP, 3, new BigDecimal("75.0")))))));

        mockMvc.perform(get("/api/reporting/analyst/retention").with(authentication(analyst())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cohorts[0].cohort").value("2026-09"))
                .andExpect(jsonPath("$.cohorts[0].months[0].monthsSinceSignUp").value(0))
                .andExpect(jsonPath("$.cohorts[0].months[0].activePct").value(75.0));
    }

    @Test
    @DisplayName("GET /activation")
    void activation() throws Exception {
        when(reports.getActivation()).thenReturn(new ActivationReport(WINDOW, List.of(new ActivationReport.Cohort(
                SEP, 2, 1, new BigDecimal("50.0"), 1, new BigDecimal("50.0"), new BigDecimal("0.5"), false))));

        mockMvc.perform(get("/api/reporting/analyst/activation").with(authentication(analyst())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cohorts[0].orderedWithin30DaysPct").value(50.0))
                .andExpect(jsonPath("$.cohorts[0].medianDaysToFirstOrder").value(0.5))
                .andExpect(jsonPath("$.cohorts[0].complete").value(false));
    }

    @Test
    @DisplayName("GET /concentration")
    void concentration() throws Exception {
        when(reports.getConcentration()).thenReturn(new ConcentrationReport(WINDOW, List.of(new ConcentrationReport.Month(
                SEP, 15, new BigDecimal("1000.00"),
                List.of(new ConcentrationReport.Tier(10, 2, new BigDecimal("550.00"), new BigDecimal("55.0")))))));

        mockMvc.perform(get("/api/reporting/analyst/concentration").with(authentication(analyst())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.months[0].tiers[0].topPct").value(10))
                .andExpect(jsonPath("$.months[0].tiers[0].sharePct").value(55.0));
    }

    @Test
    @DisplayName("GET /order-sizes - bands by name with their bounds")
    void orderSizes() throws Exception {
        when(reports.getOrderSizes()).thenReturn(new OrderSizeReport(WINDOW, List.of(new OrderSizeReport.Month(
                SEP, 1, new BigDecimal("500.00"), new BigDecimal("500.00"),
                List.of(new OrderSizeReport.Band(OrderSizeBand.UNDER_1K, null, new BigDecimal("1000"), 1, new BigDecimal("500.00")))))));

        mockMvc.perform(get("/api/reporting/analyst/order-sizes").with(authentication(analyst())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.months[0].bands[0].band").value("UNDER_1K"))
                .andExpect(jsonPath("$.months[0].bands[0].from").isEmpty())
                .andExpect(jsonPath("$.months[0].bands[0].to").value(1000));
    }
}
