package com.leap.leaplaughlove.order.ops;

import com.leap.leaplaughlove.common.security.JwtService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(TradeOpsController.class)
@DisplayName("TradeOpsController Tests")
class TradeOpsControllerTest {

    private static final UUID ORDER_ID = UUID.fromString("3f2a0000-0000-0000-0000-000000000001");
    private static final TradeSummary TRADE = new TradeSummary(ORDER_ID, OffsetDateTime.parse("2026-10-08T14:02:05Z"),
            "alice.johnson@leap.com", "ACC-1001", "AAPL", "BUY", 10, "FILLED");

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private JwtService jwtService;

    @MockBean
    private TradeTimelineService tradeTimelineService;

    @MockBean
    private OpsAccessLog accessLog;

    private static Authentication tradingOps() {
        return new UsernamePasswordAuthenticationToken(
                UUID.randomUUID(), null, List.of(new SimpleGrantedAuthority("ROLE_TRADING_OPERATIONS")));
    }

    private static TradeTimeline timeline() {
        return new TradeTimeline(TRADE, new BigDecimal("150.00"), new BigDecimal("1.00"), List.of(
                new TimelineStep(OffsetDateTime.parse("2026-10-08T14:02:05Z"), TimelineStepType.SUBMITTED,
                        "BUY 10 AAPL placed in account ACC-1001 by alice.johnson@leap.com"),
                new TimelineStep(OffsetDateTime.parse("2026-10-08T14:02:06Z"), TimelineStepType.FILLED, "Order filled")));
    }

    @Test
    @DisplayName("GET /api/order/ops/orders - 200 with the matching trades")
    void search() throws Exception {
        when(tradeTimelineService.search(null, "alice.johnson@leap.com", null, null,
                LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 8))).thenReturn(List.of(TRADE));

        mockMvc.perform(get("/api/order/ops/orders")
                        .with(authentication(tradingOps()))
                        .param("clientEmail", "alice.johnson@leap.com")
                        .param("from", "2026-10-01")
                        .param("to", "2026-10-08"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].orderId").value(ORDER_ID.toString()))
                .andExpect(jsonPath("$[0].clientEmail").value("alice.johnson@leap.com"))
                .andExpect(jsonPath("$[0].accountNumber").value("ACC-1001"))
                .andExpect(jsonPath("$[0].status").value("FILLED"));
    }

    @Test
    @DisplayName("GET /api/order/ops/orders - 400 with the service's message when no criterion is given")
    void searchWithoutCriteria() throws Exception {
        when(tradeTimelineService.search(null, null, null, null, null, null))
                .thenThrow(new IllegalArgumentException("Give at least one search criterion"));

        mockMvc.perform(get("/api/order/ops/orders").with(authentication(tradingOps())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Give at least one search criterion"));
    }

    @Test
    @DisplayName("GET /api/order/ops/orders - 400 for a malformed order ID or date")
    void searchMalformed() throws Exception {
        mockMvc.perform(get("/api/order/ops/orders").with(authentication(tradingOps())).param("orderId", "not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("orderId is not valid"));
        mockMvc.perform(get("/api/order/ops/orders").with(authentication(tradingOps())).param("from", "08/10/2026"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("from is not valid"));
        verifyNoInteractions(tradeTimelineService);
    }

    @Test
    @DisplayName("GET /api/order/ops/orders/{id}/timeline - 200 with the trade and its steps")
    void timelineJson() throws Exception {
        when(tradeTimelineService.getTimeline(ORDER_ID)).thenReturn(timeline());

        mockMvc.perform(get("/api/order/ops/orders/{id}/timeline", ORDER_ID).with(authentication(tradingOps())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.trade.orderId").value(ORDER_ID.toString()))
                .andExpect(jsonPath("$.quotedPrice").value(150.00))
                .andExpect(jsonPath("$.maxSlippagePercent").value(1.00))
                .andExpect(jsonPath("$.steps[0].type").value("SUBMITTED"))
                .andExpect(jsonPath("$.steps[0].at").value("2026-10-08T14:02:05Z"))
                .andExpect(jsonPath("$.steps[1].description").value("Order filled"));
    }

    @Test
    @DisplayName("GET /api/order/ops/orders/{id}/timeline - 404 for an unknown order")
    void timelineNotFound() throws Exception {
        when(tradeTimelineService.getTimeline(ORDER_ID))
                .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found: " + ORDER_ID));

        mockMvc.perform(get("/api/order/ops/orders/{id}/timeline", ORDER_ID).with(authentication(tradingOps())))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET /api/order/ops/orders/{id}/timeline - 400 for a malformed order ID")
    void timelineMalformedId() throws Exception {
        mockMvc.perform(get("/api/order/ops/orders/not-a-uuid/timeline").with(authentication(tradingOps())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("orderId is not valid"));
    }

    @Test
    @DisplayName("GET /api/order/ops/orders/{id}/timeline.csv - downloads the timeline as CSV")
    void timelineCsv() throws Exception {
        when(tradeTimelineService.getTimeline(ORDER_ID)).thenReturn(timeline());

        mockMvc.perform(get("/api/order/ops/orders/{id}/timeline.csv", ORDER_ID).with(authentication(tradingOps())))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", containsString("text/csv")))
                .andExpect(header().string("Content-Disposition",
                        "attachment; filename=\"trade-" + ORDER_ID + ".csv\""))
                .andExpect(content().string(containsString("order_id,time_utc,step,description\r\n")))
                .andExpect(content().string(containsString(ORDER_ID + ",2026-10-08T14:02:06.000Z,FILLED,Order filled")));
    }

    @Test
    @DisplayName("GET /api/order/ops/orders/{id}/timeline.csv - 404 for an unknown order")
    void timelineCsvNotFound() throws Exception {
        when(tradeTimelineService.getTimeline(ORDER_ID))
                .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found: " + ORDER_ID));

        mockMvc.perform(get("/api/order/ops/orders/{id}/timeline.csv", ORDER_ID).with(authentication(tradingOps())))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Each lookup is logged before any trade data is read")
    void logsBeforeReading() throws Exception {
        when(tradeTimelineService.getTimeline(ORDER_ID)).thenReturn(timeline());

        mockMvc.perform(get("/api/order/ops/orders").with(authentication(tradingOps()))
                .param("clientEmail", "alice.johnson@leap.com").param("from", "2026-10-01"));
        mockMvc.perform(get("/api/order/ops/orders/{id}/timeline", ORDER_ID).with(authentication(tradingOps())));
        mockMvc.perform(get("/api/order/ops/orders/{id}/timeline.csv", ORDER_ID).with(authentication(tradingOps())));

        InOrder order = inOrder(accessLog, tradeTimelineService);
        order.verify(accessLog).record(OpsAccessLog.Action.SEARCH, null,
                "{clientEmail=alice.johnson@leap.com, from=2026-10-01}");
        order.verify(tradeTimelineService).search(null, "alice.johnson@leap.com", null, null, LocalDate.of(2026, 10, 1), null);
        order.verify(accessLog).record(OpsAccessLog.Action.VIEW_TIMELINE, ORDER_ID, null);
        order.verify(tradeTimelineService).getTimeline(ORDER_ID);
        order.verify(accessLog).record(OpsAccessLog.Action.DOWNLOAD_CSV, ORDER_ID, null);
        order.verify(tradeTimelineService).getTimeline(ORDER_ID);
    }

    @Test
    @DisplayName("A lookup of an unknown order is still logged")
    void logsUnknownOrder() throws Exception {
        when(tradeTimelineService.getTimeline(ORDER_ID))
                .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found: " + ORDER_ID));

        mockMvc.perform(get("/api/order/ops/orders/{id}/timeline", ORDER_ID).with(authentication(tradingOps())))
                .andExpect(status().isNotFound());
        verify(accessLog).record(OpsAccessLog.Action.VIEW_TIMELINE, ORDER_ID, null);
    }

    @Test
    @DisplayName("If the access can't be logged, nothing is read or returned")
    void failsClosed() {
        doThrow(new DataAccessResourceFailureException("database down"))
                .when(accessLog).record(OpsAccessLog.Action.VIEW_TIMELINE, ORDER_ID, null);

        assertThrows(Exception.class, () -> mockMvc.perform(
                get("/api/order/ops/orders/{id}/timeline", ORDER_ID).with(authentication(tradingOps()))));
        verifyNoInteractions(tradeTimelineService);
    }
}
