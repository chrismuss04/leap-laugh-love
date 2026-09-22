package com.leap.leaplaughlove.order.order;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leap.leaplaughlove.common.security.JwtService;
import com.leap.leaplaughlove.order.client.AccountClient;
import com.leap.leaplaughlove.order.client.AccountValidationDto;
import com.leap.leaplaughlove.order.client.SettlementRequest;
import com.leap.leaplaughlove.order.client.SettlementResponse;
import com.leap.leaplaughlove.order.quote.CurrentQuoteService;
import com.leap.leaplaughlove.order.quote.QuoteSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@Sql(scripts = "/db/order_submission_test_setup.sql")
@DisplayName("Order Submission End-to-End Integration Tests")
class OrderSubmissionIntegrationTest {

    private static final String CLIENT_OWNER_ID = "11111111-1111-1111-1111-111111111111";
    private static final String CLIENT_OWNER_EMAIL = "owner@example.com";
    private static final String ACCOUNT_OWNER_USD_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String ACCOUNT_OTHER_USD_ID = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
    private static final String INSTRUMENT_AAPL_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaa0001";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JwtService jwtService;
    @Autowired private OrderRepository orderRepository;
    @Autowired private ExecutionRepository executionRepository;
    @Autowired private PositionMovementRepository positionMovementRepository;

    @MockBean private AccountClient accountClient;
    @MockBean private CurrentQuoteService currentQuoteService;

    private String ownerToken;
    private UUID accountOwnerId;
    private UUID instrumentAaplId;

    @BeforeEach
    void setUp() {
        ownerToken = jwtService.generateToken(UUID.fromString(CLIENT_OWNER_ID), CLIENT_OWNER_EMAIL);
        accountOwnerId = UUID.fromString(ACCOUNT_OWNER_USD_ID);
        instrumentAaplId = UUID.fromString(INSTRUMENT_AAPL_ID);

        when(currentQuoteService.getCurrentQuote(eq("AAPL"))).thenReturn(new QuoteSnapshot(
                "AAPL", new BigDecimal("149.50"), 100L, new BigDecimal("150.00"), 100L,
                new BigDecimal("149.75"), 100L, "NASDAQ", OffsetDateTime.now()));
        when(currentQuoteService.getCurrentQuote(eq("MSFT"))).thenReturn(new QuoteSnapshot(
                "MSFT", new BigDecimal("399.00"), 100L, new BigDecimal("400.00"), 100L,
                new BigDecimal("399.50"), 100L, "NASDAQ", OffsetDateTime.now()));
    }

    @Test
    @DisplayName("End-to-End BUY order: fills, creates audit execution, records position movement, and settles via account-app")
    void testEndToEndBuyOrder_Success() throws Exception {
        when(accountClient.getValidationData(eq(accountOwnerId), eq(instrumentAaplId)))
                .thenReturn(new AccountValidationDto(true, true, new BigDecimal("10000.00"), 25L, "USD", "ACC-OWNER-USD"));
        when(accountClient.settleOrder(eq(accountOwnerId), any(SettlementRequest.class)))
                .thenReturn(new SettlementResponse(UUID.randomUUID(), new BigDecimal("8500.00"), 35L, new BigDecimal("150.00")));

        OrderSubmissionRequest request = new OrderSubmissionRequest(
                accountOwnerId, "AAPL", null, Order.Side.BUY, 10, new BigDecimal("150.00"));

        mockMvc.perform(post("/api/order/orders")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FILLED"))
                .andExpect(jsonPath("$.symbol").value("AAPL"))
                .andExpect(jsonPath("$.quantity").value(10))
                .andExpect(jsonPath("$.execution.status").value("FILLED"))
                .andExpect(jsonPath("$.execution.fillQuantity").value(10))
                .andExpect(jsonPath("$.accountBalanceAfter").value(8500.00));

        // 1. Verify Order in DB
        List<Order> orders = orderRepository.findAll();
        Order savedOrder = orders.stream()
                .filter(o -> o.getAccountId().equals(accountOwnerId))
                .findFirst()
                .orElseThrow();
        assertEquals(Order.Status.FILLED, savedOrder.getStatus());
        assertNotNull(savedOrder.getSubmittedAt());
        assertNotNull(savedOrder.getAcceptedAt());
        assertNotNull(savedOrder.getFilledAt());

        // 2. Verify Execution in DB
        List<Execution> executions = executionRepository.findByOrder_OrderIdIn(List.of(savedOrder.getOrderId()));
        assertEquals(1, executions.size());
        Execution execution = executions.get(0);
        assertEquals(Execution.Status.FILLED, execution.getStatus());
        assertEquals(10L, execution.getFillQuantity());
        assertEquals(0, new BigDecimal("150.0000").compareTo(execution.getFillPrice()));

        // 3. Verify Position Movement in DB (+10 shares)
        List<PositionMovement> movements = positionMovementRepository.findByAccountIdAndInstrumentId(accountOwnerId, instrumentAaplId);
        assertFalse(movements.isEmpty());
        PositionMovement latestMove = movements.get(movements.size() - 1);
        assertEquals("BUY_FILL", latestMove.getMovementType());
        assertEquals(10L, latestMove.getQuantityDelta());
        assertEquals(savedOrder.getOrderId(), latestMove.getOrderId());
        assertEquals(execution.getExecutionId(), latestMove.getExecutionId());

        // 4. Verify settlement called
        verify(accountClient).settleOrder(eq(accountOwnerId), any(SettlementRequest.class));
    }

    @Test
    @DisplayName("End-to-End SELL order: fills, records position movement, and settles via account-app")
    void testEndToEndSellOrder_Success() throws Exception {
        when(accountClient.getValidationData(eq(accountOwnerId), eq(instrumentAaplId)))
                .thenReturn(new AccountValidationDto(true, true, new BigDecimal("10000.00"), 25L, "USD", "ACC-OWNER-USD"));
        when(accountClient.settleOrder(eq(accountOwnerId), any(SettlementRequest.class)))
                .thenReturn(new SettlementResponse(UUID.randomUUID(), new BigDecimal("11000.00"), 20L, new BigDecimal("183.50")));

        OrderSubmissionRequest request = new OrderSubmissionRequest(
                accountOwnerId, "AAPL", null, Order.Side.SELL, 5, new BigDecimal("200.00"));

        mockMvc.perform(post("/api/order/orders")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FILLED"))
                .andExpect(jsonPath("$.side").value("SELL"))
                .andExpect(jsonPath("$.quantity").value(5))
                .andExpect(jsonPath("$.execution.status").value("FILLED"))
                .andExpect(jsonPath("$.accountBalanceAfter").value(11000.00));

        // Verify Position Movement in DB (-5 shares)
        List<PositionMovement> movements = positionMovementRepository.findByAccountIdAndInstrumentId(accountOwnerId, instrumentAaplId);
        assertFalse(movements.isEmpty());
        PositionMovement latestMove = movements.get(movements.size() - 1);
        assertEquals("SELL_FILL", latestMove.getMovementType());
        assertEquals(-5L, latestMove.getQuantityDelta());

        verify(accountClient).settleOrder(eq(accountOwnerId), any(SettlementRequest.class));
    }

    @Test
    @DisplayName("End-to-End Rejected BUY order (insufficient funds): stores REJECTED order & execution, skips settlement")
    void testEndToEndBuyOrder_RejectedInsufficientFunds() throws Exception {
        when(accountClient.getValidationData(eq(accountOwnerId), eq(instrumentAaplId)))
                .thenReturn(new AccountValidationDto(true, true, new BigDecimal("10000.00"), 25L, "USD", "ACC-OWNER-USD"));

        // Order costing $1,500,000 exceeding available $10,000
        OrderSubmissionRequest request = new OrderSubmissionRequest(
                accountOwnerId, "AAPL", null, Order.Side.BUY, 10000, new BigDecimal("150.00"));

        mockMvc.perform(post("/api/order/orders")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.rejectionReason").value("Insufficient funds - order rejected"))
                .andExpect(jsonPath("$.execution.status").value("REJECTED"))
                .andExpect(jsonPath("$.execution.reason").value("Insufficient funds - order rejected"));

        verify(accountClient, never()).settleOrder(any(), any());
    }

    @Test
    @DisplayName("End-to-End Rejected SELL order (insufficient holdings): stores REJECTED order & execution, skips settlement")
    void testEndToEndSellOrder_RejectedInsufficientPosition() throws Exception {
        when(accountClient.getValidationData(eq(accountOwnerId), eq(instrumentAaplId)))
                .thenReturn(new AccountValidationDto(true, true, new BigDecimal("10000.00"), 25L, "USD", "ACC-OWNER-USD"));

        // Client only holds 25 AAPL, attempts to sell 100
        OrderSubmissionRequest request = new OrderSubmissionRequest(
                accountOwnerId, "AAPL", null, Order.Side.SELL, 100, new BigDecimal("150.00"));

        mockMvc.perform(post("/api/order/orders")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.rejectionReason").value("Insufficient position quantity - order rejected"))
                .andExpect(jsonPath("$.execution.status").value("REJECTED"));

        verify(accountClient, never()).settleOrder(any(), any());
    }

    @Test
    @DisplayName("Submitting order for unowned account returns 404")
    void testSubmitOrder_UnownedAccount_Returns404() throws Exception {
        UUID otherAccountId = UUID.fromString(ACCOUNT_OTHER_USD_ID);
        when(accountClient.getValidationData(eq(otherAccountId), eq(instrumentAaplId)))
                .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "Account not found"));

        OrderSubmissionRequest request = new OrderSubmissionRequest(
                otherAccountId, "AAPL", null, Order.Side.BUY, 10, new BigDecimal("150.00"));

        mockMvc.perform(post("/api/order/orders")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("End-to-End market BUY order without explicit price: uses askPrice from CurrentQuoteService")
    void testEndToEndMarketOrder_NullPrice_UsesCurrentQuoteServiceAskPrice() throws Exception {
        when(accountClient.getValidationData(eq(accountOwnerId), eq(instrumentAaplId)))
                .thenReturn(new AccountValidationDto(true, true, new BigDecimal("10000.00"), 25L, "USD", "ACC-OWNER-USD"));
        when(accountClient.settleOrder(eq(accountOwnerId), any(SettlementRequest.class)))
                .thenReturn(new SettlementResponse(UUID.randomUUID(), new BigDecimal("8500.00"), 35L, new BigDecimal("150.00")));

        OrderSubmissionRequest request = new OrderSubmissionRequest(
                accountOwnerId, "AAPL", null, Order.Side.BUY, 10, null);

        mockMvc.perform(post("/api/order/orders")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FILLED"))
                .andExpect(jsonPath("$.execution.status").value("FILLED"))
                .andExpect(jsonPath("$.execution.fillPrice").value(150.00))
                .andExpect(jsonPath("$.accountBalanceAfter").value(8500.00));
    }
}
