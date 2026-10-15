package com.leap.leaplaughlove.order.ops;

import com.leap.leaplaughlove.common.security.JwtService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(TradeOpsReportController.class)
@DisplayName("TradeOpsReportController Tests")
class TradeOpsReportControllerTest {

    private static final OffsetDateTime FROM = OffsetDateTime.parse("2026-10-01T00:00:00Z");
    private static final OffsetDateTime TO = OffsetDateTime.parse("2026-10-08T00:00:00Z");
    private static final UUID ORDER_ID = UUID.fromString("3f2a0000-0000-0000-0000-000000000001");

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private JwtService jwtService;

    @MockBean
    private TradeOpsReportService tradeOpsReportService;

    @MockBean
    private OpsAccessLog accessLog;

    private static Authentication tradingOps() {
        return new UsernamePasswordAuthenticationToken(
                UUID.randomUUID(), null, List.of(new SimpleGrantedAuthority("ROLE_TRADING_OPERATIONS")));
    }

    @Test
    @DisplayName("GET /api/order/ops/reports/rejections - 200 with the period's rejections by reason")
    void rejections() throws Exception {
        when(tradeOpsReportService.rejectionsByReason(FROM, TO)).thenReturn(List.of(
                new RejectionCount("Insufficient position", 3, OffsetDateTime.parse("2026-10-05T10:00:01Z")),
                new RejectionCount(null, 1, OffsetDateTime.parse("2026-10-04T09:00:00Z"))));

        mockMvc.perform(get("/api/order/ops/reports/rejections").with(authentication(tradingOps()))
                        .param("from", "2026-10-01T00:00:00Z").param("to", "2026-10-08T00:00:00Z"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].reason").value("Insufficient position"))
                .andExpect(jsonPath("$[0].count").value(3))
                .andExpect(jsonPath("$[0].lastRejectedAt").value("2026-10-05T10:00:01Z"))
                .andExpect(jsonPath("$[1].reason").isEmpty());
    }

    @Test
    @DisplayName("GET /api/order/ops/reports/rejections - 400 with the service's message without a period")
    void rejectionsWithoutPeriod() throws Exception {
        when(tradeOpsReportService.rejectionsByReason(null, null))
                .thenThrow(new IllegalArgumentException("Give a time period to report on"));

        mockMvc.perform(get("/api/order/ops/reports/rejections").with(authentication(tradingOps())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Give a time period to report on"));
    }

    @Test
    @DisplayName("GET /api/order/ops/reports/rejections - 400 for a malformed date-time")
    void rejectionsMalformed() throws Exception {
        mockMvc.perform(get("/api/order/ops/reports/rejections").with(authentication(tradingOps()))
                        .param("from", "08/10/2026").param("to", "2026-10-08T00:00:00Z"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("from is not valid"));
        verifyNoInteractions(tradeOpsReportService);
    }

    @Test
    @DisplayName("GET /api/order/ops/reports/stuck-orders - 200 with the orders still waiting to execute")
    void stuckOrders() throws Exception {
        when(tradeOpsReportService.stuckOrders()).thenReturn(List.of(new TradeSummary(ORDER_ID,
                OffsetDateTime.parse("2026-10-08T13:00:00Z"), "alice.johnson@leap.com", "ACC-1001", "AAPL", "BUY", 10,
                "SUBMITTED")));

        mockMvc.perform(get("/api/order/ops/reports/stuck-orders").with(authentication(tradingOps())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].orderId").value(ORDER_ID.toString()))
                .andExpect(jsonPath("$[0].status").value("SUBMITTED"));
    }

    @Test
    @DisplayName("Each report is logged before any data is read")
    void logsBeforeReading() throws Exception {
        mockMvc.perform(get("/api/order/ops/reports/rejections").with(authentication(tradingOps()))
                .param("from", "2026-10-01T00:00:00Z").param("to", "2026-10-08T00:00:00Z"));
        mockMvc.perform(get("/api/order/ops/reports/stuck-orders").with(authentication(tradingOps())));

        InOrder order = inOrder(accessLog, tradeOpsReportService);
        order.verify(accessLog).record(OpsAccessLog.Action.VIEW_REJECTIONS, null,
                "{from=2026-10-01T00:00Z, to=2026-10-08T00:00Z}");
        order.verify(tradeOpsReportService).rejectionsByReason(FROM, TO);
        order.verify(accessLog).record(OpsAccessLog.Action.VIEW_STUCK_ORDERS, null, null);
        order.verify(tradeOpsReportService).stuckOrders();
    }

    @Test
    @DisplayName("A report request that turns out invalid is still logged")
    void logsInvalidRequest() throws Exception {
        when(tradeOpsReportService.rejectionsByReason(null, null))
                .thenThrow(new IllegalArgumentException("Give a time period to report on"));

        mockMvc.perform(get("/api/order/ops/reports/rejections").with(authentication(tradingOps())))
                .andExpect(status().isBadRequest());
        verify(accessLog).record(OpsAccessLog.Action.VIEW_REJECTIONS, null, "{}");
    }

    @Test
    @DisplayName("If the access can't be logged, nothing is read or returned")
    void failsClosed() {
        doThrow(new DataAccessResourceFailureException("database down"))
                .when(accessLog).record(OpsAccessLog.Action.VIEW_STUCK_ORDERS, null, null);

        assertThrows(Exception.class, () -> mockMvc.perform(
                get("/api/order/ops/reports/stuck-orders").with(authentication(tradingOps()))));
        verifyNoInteractions(tradeOpsReportService);
    }
}
