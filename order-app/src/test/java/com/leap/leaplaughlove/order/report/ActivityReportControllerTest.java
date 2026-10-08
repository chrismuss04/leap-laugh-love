package com.leap.leaplaughlove.order.report;

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
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ActivityReportController.class)
@DisplayName("ActivityReportController Tests")
class ActivityReportControllerTest {

    private static final LocalDate FROM = LocalDate.of(2026, 9, 28);
    private static final LocalDate TO = LocalDate.of(2026, 10, 4);

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private JwtService jwtService;

    @MockBean
    private ActivityReportService activityReportService;

    private static Authentication analyst() {
        return new UsernamePasswordAuthenticationToken(
                UUID.randomUUID(), null, List.of(new SimpleGrantedAuthority("ROLE_COMMERCIAL_ANALYST")));
    }

    @Test
    @DisplayName("GET /api/order/reports/activity - 200 with totals and buckets")
    void activityReport() throws Exception {
        UUID aaplId = UUID.randomUUID();
        ActivityTotals totals = new ActivityTotals(3, 2, 1,
                new BigDecimal("1102.50"), new BigDecimal("600.00"), 19);
        ActivityReport report = new ActivityReport(FROM, TO, ReportGranularity.WEEKLY,
                new ReportInstrument(aaplId, "AAPL"), totals, List.of(new ActivityBucket(FROM, totals)));
        when(activityReportService.getActivityReport(FROM, TO, ReportGranularity.WEEKLY, aaplId)).thenReturn(report);

        mockMvc.perform(get("/api/order/reports/activity")
                        .with(authentication(analyst()))
                        .param("from", "2026-09-28")
                        .param("to", "2026-10-04")
                        .param("granularity", "WEEKLY")
                        .param("instrumentId", aaplId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.from").value("2026-09-28"))
                .andExpect(jsonPath("$.to").value("2026-10-04"))
                .andExpect(jsonPath("$.granularity").value("WEEKLY"))
                .andExpect(jsonPath("$.instrument.symbol").value("AAPL"))
                .andExpect(jsonPath("$.totals.tradeCount").value(3))
                .andExpect(jsonPath("$.totals.buyCount").value(2))
                .andExpect(jsonPath("$.totals.sellCount").value(1))
                .andExpect(jsonPath("$.totals.buyValue").value(1102.50))
                .andExpect(jsonPath("$.totals.sellValue").value(600.00))
                .andExpect(jsonPath("$.totals.volume").value(19))
                .andExpect(jsonPath("$.buckets[0].periodStart").value("2026-09-28"))
                .andExpect(jsonPath("$.buckets[0].totals.tradeCount").value(3));
    }

    @Test
    @DisplayName("GET /api/order/reports/activity - granularity defaults to DAILY, instrument to all")
    void defaults() throws Exception {
        when(activityReportService.getActivityReport(FROM, TO, ReportGranularity.DAILY, null))
                .thenReturn(new ActivityReport(FROM, TO, ReportGranularity.DAILY, null, ActivityTotals.EMPTY, List.of()));

        mockMvc.perform(get("/api/order/reports/activity")
                        .with(authentication(analyst()))
                        .param("from", "2026-09-28")
                        .param("to", "2026-10-04"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.granularity").value("DAILY"))
                .andExpect(jsonPath("$.instrument").isEmpty());
    }

    @Test
    @DisplayName("GET /api/order/reports/activity - 200 with empty buckets and zero totals when nothing traded")
    void emptyPeriod() throws Exception {
        when(activityReportService.getActivityReport(FROM, TO, ReportGranularity.DAILY, null))
                .thenReturn(new ActivityReport(FROM, TO, ReportGranularity.DAILY, null, ActivityTotals.EMPTY, List.of()));

        mockMvc.perform(get("/api/order/reports/activity")
                        .with(authentication(analyst()))
                        .param("from", "2026-09-28")
                        .param("to", "2026-10-04"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.buckets").isEmpty())
                .andExpect(jsonPath("$.totals.tradeCount").value(0));
    }

    @Test
    @DisplayName("GET /api/order/reports/activity - 400 with the service's message for an invalid period")
    void invalidPeriod() throws Exception {
        when(activityReportService.getActivityReport(TO, FROM, ReportGranularity.DAILY, null))
                .thenThrow(new IllegalArgumentException("from must be on or before to"));

        mockMvc.perform(get("/api/order/reports/activity")
                        .with(authentication(analyst()))
                        .param("from", "2026-10-04")
                        .param("to", "2026-09-28"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("from must be on or before to"));
    }

    @Test
    @DisplayName("GET /api/order/reports/activity - 400 when from is missing")
    void missingFrom() throws Exception {
        mockMvc.perform(get("/api/order/reports/activity")
                        .with(authentication(analyst()))
                        .param("to", "2026-10-04"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("from is required"));
        verifyNoInteractions(activityReportService);
    }

    @Test
    @DisplayName("GET /api/order/reports/activity - 400 for a malformed date, granularity or instrument ID")
    void malformedParameters() throws Exception {
        mockMvc.perform(get("/api/order/reports/activity")
                        .with(authentication(analyst()))
                        .param("from", "28/09/2026")
                        .param("to", "2026-10-04"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("from is not valid"));
        mockMvc.perform(get("/api/order/reports/activity")
                        .with(authentication(analyst()))
                        .param("from", "2026-09-28")
                        .param("to", "2026-10-04")
                        .param("granularity", "MONTHLY"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("granularity is not valid"));
        mockMvc.perform(get("/api/order/reports/activity")
                        .with(authentication(analyst()))
                        .param("from", "2026-09-28")
                        .param("to", "2026-10-04")
                        .param("instrumentId", "AAPL"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("instrumentId is not valid"));
        verifyNoInteractions(activityReportService);
    }

    @Test
    @DisplayName("GET /api/order/reports/instruments - 200 with the instruments by symbol")
    void instruments() throws Exception {
        UUID aaplId = UUID.randomUUID();
        when(activityReportService.getInstruments()).thenReturn(List.of(new ReportInstrument(aaplId, "AAPL")));

        mockMvc.perform(get("/api/order/reports/instruments").with(authentication(analyst())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(aaplId.toString()))
                .andExpect(jsonPath("$[0].symbol").value("AAPL"));
    }
}
