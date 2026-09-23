package com.leap.leaplaughlove.trading.order;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.domain.PageImpl;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(OrderHistoryController.class)
class OrderHistoryControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private OrderHistoryService orderHistoryService;

    private static Authentication createAuthenticationWithClientId(UUID clientId) {
        return new UsernamePasswordAuthenticationToken(
                clientId, null, List.of(new SimpleGrantedAuthority("ROLE_CLIENT")));
    }

    @Test
    @DisplayName("GET /api/trading/orders/history - 200 with paginated history items for authenticated client")
    void testGetOrderHistorySuccess() throws Exception {
        UUID clientId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        UUID executionId = UUID.randomUUID();
        OffsetDateTime submitted = OffsetDateTime.now();
        BigDecimal price = new BigDecimal("150.25");

        ExecutionItem execution = new ExecutionItem(executionId, 10L, price, submitted.plusSeconds(30));
        OrderHistoryItem item = new OrderHistoryItem(
                orderId, "AAPL", "BUY", 10L, "FILLED", submitted, submitted.plusSeconds(30), List.of(execution));

        when(orderHistoryService.getOrderHistory(clientId, 0, 20, null, null, null))
                .thenReturn(new PageImpl<>(List.of(item)));

        mockMvc.perform(get("/api/trading/orders/history")
                        .with(csrf())
                        .with(authentication(createAuthenticationWithClientId(clientId)))
                        .param("page", "0")
                        .param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].orderId").value(orderId.toString()))
                .andExpect(jsonPath("$.content[0].symbol").value("AAPL"))
                .andExpect(jsonPath("$.content[0].side").value("BUY"))
                .andExpect(jsonPath("$.content[0].status").value("FILLED"))
                .andExpect(jsonPath("$.content[0].execution.executionId").value(executionId.toString()))
                .andExpect(jsonPath("$.content[0].execution.quantity").value(10))
                .andExpect(jsonPath("$.content[0].executions[0].executionId").value(executionId.toString()));
    }

    @Test
    @DisplayName("GET /api/trading/orders/history - 400 when invalid page param")
    void testGetOrderHistoryInvalidParams() throws Exception {
        UUID clientId = UUID.randomUUID();

        when(orderHistoryService.getOrderHistory(clientId, -1, 20, null, null, null))
                .thenThrow(new IllegalArgumentException("page must not be negative"));

        mockMvc.perform(get("/api/trading/orders/history")
                        .with(csrf())
                        .with(authentication(createAuthenticationWithClientId(clientId)))
                        .param("page", "-1")
                        .param("size", "20"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("page must not be negative"));
    }

    @Test
    @DisplayName("GET /api/trading/orders/history - passes year, month and day filters through to the service")
    void testGetOrderHistoryWithDateFilters() throws Exception {
        UUID clientId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        OffsetDateTime submitted = OffsetDateTime.now();
        OrderHistoryItem item = new OrderHistoryItem(
                orderId, "AAPL", "BUY", 50L, "FILLED", submitted, submitted.plusSeconds(60), List.of());

        when(orderHistoryService.getOrderHistory(clientId, 0, 20, 2026, 8, 3))
                .thenReturn(new PageImpl<>(List.of(item)));

        mockMvc.perform(get("/api/trading/orders/history")
                        .with(csrf())
                        .with(authentication(createAuthenticationWithClientId(clientId)))
                        .param("year", "2026")
                        .param("month", "8")
                        .param("day", "3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].orderId").value(orderId.toString()));
    }

    @Test
    @DisplayName("GET /api/trading/orders/history - 400 when the date filter combination is invalid")
    void testGetOrderHistoryInvalidDateFilter() throws Exception {
        UUID clientId = UUID.randomUUID();

        when(orderHistoryService.getOrderHistory(clientId, 0, 20, null, 8, null))
                .thenThrow(new IllegalArgumentException("year is required when month or day is provided"));

        mockMvc.perform(get("/api/trading/orders/history")
                        .with(csrf())
                        .with(authentication(createAuthenticationWithClientId(clientId)))
                        .param("month", "8"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("year is required when month or day is provided"));
    }

    @Test
    @DisplayName("GET /api/trading/orders/history - 400 when a filter is not a number")
    void testGetOrderHistoryNonNumericFilter() throws Exception {
        UUID clientId = UUID.randomUUID();

        mockMvc.perform(get("/api/trading/orders/history")
                        .with(csrf())
                        .with(authentication(createAuthenticationWithClientId(clientId)))
                        .param("year", "abc"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithAnonymousUser
    @DisplayName("GET /api/trading/orders/history - 401 when unauthenticated")
    void testGetOrderHistoryUnauthenticated() throws Exception {
        mockMvc.perform(get("/api/trading/orders/history")
                        .param("page", "0")
                        .param("size", "20"))
                .andExpect(status().isUnauthorized());
    }
}
