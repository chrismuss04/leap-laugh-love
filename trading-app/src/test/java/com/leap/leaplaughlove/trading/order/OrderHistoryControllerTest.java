package com.leap.leaplaughlove.trading.order;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.domain.PageImpl;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Collection;
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
            public void setAuthenticated(boolean isAuthenticated) throws IllegalArgumentException {
            }

            @Override
            public String getName() {
                return clientId.toString();
            }
        };
    }

    @Test
    @DisplayName("GET /api/trading/orders/history - 200 with paginated history items for authenticated client")
    void testGetOrderHistorySuccess() throws Exception {
        UUID clientId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        OffsetDateTime submitted = OffsetDateTime.now();

        OrderHistoryItem item = new OrderHistoryItem(
                orderId, "AAPL", "BUY", new BigDecimal("10.0000"), "FILLED", submitted, submitted.plusSeconds(30));

        when(orderHistoryService.getOrderHistory(clientId, 0, 20))
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
                .andExpect(jsonPath("$.content[0].status").value("FILLED"));
    }

    @Test
    @DisplayName("GET /api/trading/orders/history - 400 when invalid page param")
    void testGetOrderHistoryInvalidParams() throws Exception {
        UUID clientId = UUID.randomUUID();

        when(orderHistoryService.getOrderHistory(clientId, -1, 20))
                .thenThrow(new IllegalArgumentException("page must not be negative"));

        mockMvc.perform(get("/api/trading/orders/history")
                        .with(csrf())
                        .with(authentication(createAuthenticationWithClientId(clientId)))
                        .param("page", "-1")
                        .param("size", "20"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$").value("page must not be negative"));
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
