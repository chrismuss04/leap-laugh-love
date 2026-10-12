package com.leap.leaplaughlove.order.submission;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leap.leaplaughlove.common.security.JwtService;
import com.leap.leaplaughlove.order.order.Order;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
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

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(OrderSubmissionController.class)
@DisplayName("OrderSubmissionController Tests")
class OrderSubmissionControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private JwtService jwtService;

    @MockBean
    private OrderSubmissionService orderSubmissionService;

    private static Authentication createAuthenticationWithClientId(UUID clientId) {
        return new Authentication() {
            @Override
            public Collection<? extends GrantedAuthority> getAuthorities() {
                return List.of(new SimpleGrantedAuthority("ROLE_CLIENT"));
            }

            @Override
            public Object getCredentials() { return null; }

            @Override
            public Object getDetails() { return null; }

            @Override
            public Object getPrincipal() { return clientId; }

            @Override
            public boolean isAuthenticated() { return true; }

            @Override
            public void setAuthenticated(boolean isAuthenticated) throws IllegalArgumentException {}

            @Override
            public String getName() { return clientId.toString(); }
        };
    }

    @Test
    @DisplayName("POST /api/order/orders - 200 with FILLED response when valid request")
    void testSubmitOrder_Success() throws Exception {
        UUID clientId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        UUID execId = UUID.randomUUID();
        UUID instId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();

        OrderSubmissionResponse.ExecutionDto execDto = new OrderSubmissionResponse.ExecutionDto(
                execId, 10L, new BigDecimal("150.00"), "FILLED", now, "Executed at market price");

        OrderSubmissionResponse mockResponse = new OrderSubmissionResponse(
                orderId, accountId, "ACC-01", instId, "AAPL", "BUY", 10L, "FILLED",
                now, now, null, now, null, execDto, new BigDecimal("8500.00"));

        when(orderSubmissionService.submitOrder(any(OrderSubmissionRequest.class))).thenReturn(mockResponse);

        OrderSubmissionRequest request = new OrderSubmissionRequest(
                accountId, "AAPL", null, Order.Side.BUY, 10, new BigDecimal("150.00"), null);

        mockMvc.perform(post("/api/order/orders")
                        .with(csrf())
                        .with(authentication(createAuthenticationWithClientId(clientId)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderId").value(orderId.toString()))
                .andExpect(jsonPath("$.symbol").value("AAPL"))
                .andExpect(jsonPath("$.side").value("BUY"))
                .andExpect(jsonPath("$.quantity").value(10))
                .andExpect(jsonPath("$.status").value("FILLED"))
                .andExpect(jsonPath("$.execution.status").value("FILLED"))
                .andExpect(jsonPath("$.accountBalanceAfter").value(8500.00));
    }

    @Test
    @DisplayName("POST /api/order/orders - 400 when quantity is 0")
    void testSubmitOrder_InvalidQuantity_BadRequest() throws Exception {
        UUID clientId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();

        OrderSubmissionRequest request = new OrderSubmissionRequest(
                accountId, "AAPL", null, Order.Side.BUY, 0, new BigDecimal("150.00"), null);

        mockMvc.perform(post("/api/order/orders")
                        .with(csrf())
                        .with(authentication(createAuthenticationWithClientId(clientId)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithAnonymousUser
    @DisplayName("POST /api/order/orders - 401 when unauthenticated")
    void testSubmitOrder_Unauthenticated() throws Exception {
        OrderSubmissionRequest request = new OrderSubmissionRequest(
                UUID.randomUUID(), "AAPL", null, Order.Side.BUY, 10, new BigDecimal("150.00"), null);

        mockMvc.perform(post("/api/order/orders")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized());
    }

    /** Posts an order for 10 AAPL with the given price-tolerance fields and expects a 400 naming the problem. */
    private void assertToleranceRejected(String toleranceFields, String expectedMessage) throws Exception {
        String body = "{\"accountId\":\"" + UUID.randomUUID() + "\",\"symbol\":\"AAPL\",\"side\":\"BUY\","
                + "\"quantity\":10," + toleranceFields + "}";

        mockMvc.perform(post("/api/order/orders")
                        .with(csrf())
                        .with(authentication(createAuthenticationWithClientId(UUID.randomUUID())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString(expectedMessage)));
        verify(orderSubmissionService, never()).submitOrder(any());
    }

    @Test
    @DisplayName("POST /api/order/orders - 400 when a price tolerance is given without a quoted price")
    void testSubmitOrder_ToleranceWithoutQuotedPrice_BadRequest() throws Exception {
        assertToleranceRejected("\"maxSlippagePercent\":1.00",
                "quotedPrice is required when maxSlippagePercent is set");
    }

    @Test
    @DisplayName("POST /api/order/orders - 400 when the price tolerance is negative")
    void testSubmitOrder_NegativeTolerance_BadRequest() throws Exception {
        assertToleranceRejected("\"quotedPrice\":150.00,\"maxSlippagePercent\":-0.5",
                "maxSlippagePercent must be between 0 and 10");
    }

    @Test
    @DisplayName("POST /api/order/orders - 400 when the price tolerance is above 10%")
    void testSubmitOrder_ToleranceAboveCap_BadRequest() throws Exception {
        assertToleranceRejected("\"quotedPrice\":150.00,\"maxSlippagePercent\":10.01",
                "maxSlippagePercent must be between 0 and 10");
    }

    @Test
    @DisplayName("POST /api/order/orders - 400 when the price tolerance has more than two decimals")
    void testSubmitOrder_TolerancePrecision_BadRequest() throws Exception {
        assertToleranceRejected("\"quotedPrice\":150.00,\"maxSlippagePercent\":1.255",
                "maxSlippagePercent may have at most two decimal places");
    }

    @Test
    @DisplayName("POST /api/order/orders - 400 when the quoted price is not positive")
    void testSubmitOrder_NonPositiveQuotedPrice_BadRequest() throws Exception {
        assertToleranceRejected("\"quotedPrice\":0,\"maxSlippagePercent\":1.00",
                "quotedPrice must be greater than zero");
    }
}
