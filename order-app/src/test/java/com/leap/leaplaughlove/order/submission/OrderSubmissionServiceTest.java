package com.leap.leaplaughlove.order.submission;

import com.leap.leaplaughlove.order.client.AccountClient;
import com.leap.leaplaughlove.order.client.AccountValidationDto;
import com.leap.leaplaughlove.order.client.SettlementRequest;
import com.leap.leaplaughlove.order.client.SettlementResponse;
import com.leap.leaplaughlove.order.execution.Execution;
import com.leap.leaplaughlove.order.execution.ExecutionRepository;
import com.leap.leaplaughlove.order.history.FillRecorder;
import com.leap.leaplaughlove.order.instrument.Instrument;
import com.leap.leaplaughlove.order.instrument.InstrumentRepository;
import com.leap.leaplaughlove.order.order.Order;
import com.leap.leaplaughlove.order.order.OrderRepository;
import com.leap.leaplaughlove.order.position.PositionMovement;
import com.leap.leaplaughlove.order.position.PositionMovementRepository;
import com.leap.leaplaughlove.order.quote.CurrentQuoteService;
import com.leap.leaplaughlove.order.quote.QuoteSnapshot;
import com.leap.leaplaughlove.order.quote.QuoteUnavailableException;
import com.leap.leaplaughlove.order.validation.TradeValidationResult;
import com.leap.leaplaughlove.order.validation.TradeValidationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("OrderSubmissionService Tests")
class OrderSubmissionServiceTest {

    @Mock private AccountClient accountClient;
    @Mock private InstrumentRepository instrumentRepository;
    @Mock private OrderRepository orderRepository;
    @Mock private ExecutionRepository executionRepository;
    @Mock private PositionMovementRepository positionMovementRepository;
    @Mock private TradeValidationService tradeValidationService;
    @Mock private CurrentQuoteService currentQuoteService;
    @Mock private PlatformTransactionManager transactionManager;

    private OrderSubmissionService orderSubmissionService;
    private FillRecorder fillRecorder;

    private UUID accountId;
    private Instrument instrument;
    private AccountValidationDto validationDto;
    /** The order as last saved, which findById serves back like the database would. */
    private Order storedOrder;

    @BeforeEach
    void setUp() {
        fillRecorder = new FillRecorder(executionRepository, positionMovementRepository, accountClient);
        orderSubmissionService = new OrderSubmissionService(
                accountClient,
                instrumentRepository,
                orderRepository,
                executionRepository,
                fillRecorder,
                tradeValidationService,
                currentQuoteService,
                new TransactionTemplate(transactionManager)
        );

        accountId = UUID.randomUUID();
        instrument = new Instrument(UUID.randomUUID(), "AAPL", "Apple Inc.", "EQUITY", "NASDAQ", "USD", true);
        validationDto = new AccountValidationDto(true, true, new BigDecimal("10000.00"), 0L, "USD", "ACC-TEST-01");

        when(instrumentRepository.findBySymbol("AAPL")).thenReturn(Optional.of(instrument));
        when(orderRepository.saveAndFlush(any(Order.class))).thenAnswer(invocation -> {
            storedOrder = invocation.getArgument(0);
            return storedOrder;
        });
        lenient().when(orderRepository.findByIdForUpdate(any())).thenAnswer(invocation -> Optional.ofNullable(storedOrder));
        when(executionRepository.saveAndFlush(any(Execution.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    @DisplayName("Successful BUY order: SUBMITTED -> ACCEPTED -> FILLED, updates execution, position movements, and settles via account-app")
    void testSuccessfulBuyOrder() {
        OrderSubmissionRequest request = new OrderSubmissionRequest(
                accountId, "AAPL", null, Order.Side.BUY, 10, new BigDecimal("150.00"));

        when(accountClient.getValidationData(eq(accountId), eq(instrument.getInstrumentId())))
                .thenReturn(validationDto);
        when(tradeValidationService.validateTrade(eq(validationDto), eq(instrument), eq(Order.Side.BUY), eq(10L), eq(new BigDecimal("150.0000"))))
                .thenReturn(TradeValidationResult.accepted());
        when(accountClient.settleOrder(eq(accountId), any(SettlementRequest.class)))
                .thenReturn(new SettlementResponse(UUID.randomUUID(), new BigDecimal("8500.00"), 10L, new BigDecimal("150.00")));

        OrderSubmissionResponse response = orderSubmissionService.submitOrder(request);

        assertNotNull(response);
        assertEquals("FILLED", response.status());
        assertEquals("AAPL", response.symbol());
        assertEquals(10L, response.quantity());
        assertNotNull(response.submittedAt());
        assertNotNull(response.acceptedAt());
        assertNotNull(response.filledAt());
        assertNull(response.rejectionReason());

        // Verify Execution record stored as FILLED
        assertEquals("FILLED", response.execution().status());
        assertEquals(10L, response.execution().fillQuantity());
        assertEquals(new BigDecimal("150.0000"), response.execution().fillPrice());

        // Verify Position Movement saved with positive quantity delta
        ArgumentCaptor<PositionMovement> moveCaptor = ArgumentCaptor.forClass(PositionMovement.class);
        verify(positionMovementRepository).save(moveCaptor.capture());
        PositionMovement movement = moveCaptor.getValue();
        assertEquals(10L, movement.getQuantityDelta());
        assertEquals(new BigDecimal("1500.00"), movement.getCostDelta());
        assertEquals("BUY_FILL", movement.getMovementType());

        // Verify settlement client called
        verify(accountClient).settleOrder(eq(accountId), any(SettlementRequest.class));
    }

    @Test
    @DisplayName("Successful SELL order: SUBMITTED -> ACCEPTED -> FILLED, updates position movement and settles via account-app")
    void testSuccessfulSellOrder() {
        OrderSubmissionRequest request = new OrderSubmissionRequest(
                accountId, "AAPL", null, Order.Side.SELL, 5, new BigDecimal("160.00"));

        AccountValidationDto sellValidation = new AccountValidationDto(true, true, new BigDecimal("10000.00"), 20L, "USD", "ACC-TEST-01");
        when(accountClient.getValidationData(eq(accountId), eq(instrument.getInstrumentId())))
                .thenReturn(sellValidation);
        when(tradeValidationService.validateTrade(eq(sellValidation), eq(instrument), eq(Order.Side.SELL), eq(5L), eq(new BigDecimal("160.0000"))))
                .thenReturn(TradeValidationResult.accepted());
        when(accountClient.settleOrder(eq(accountId), any(SettlementRequest.class)))
                .thenReturn(new SettlementResponse(UUID.randomUUID(), new BigDecimal("10800.00"), 15L, new BigDecimal("140.00")));

        OrderSubmissionResponse response = orderSubmissionService.submitOrder(request);

        assertNotNull(response);
        assertEquals("FILLED", response.status());
        assertEquals("SELL", response.side());

        // Verify position movement has negative quantity delta
        ArgumentCaptor<PositionMovement> moveCaptor = ArgumentCaptor.forClass(PositionMovement.class);
        verify(positionMovementRepository).save(moveCaptor.capture());
        PositionMovement movement = moveCaptor.getValue();
        assertEquals(-5L, movement.getQuantityDelta());
        assertEquals("SELL_FILL", movement.getMovementType());

        // Verify settlement
        verify(accountClient).settleOrder(eq(accountId), any(SettlementRequest.class));
    }

    // LLL-133
    @Test
    @DisplayName("Sell settlement fails when the validated position is missing")
    void testSellSettlement_MissingPosition_Throws() {
        OrderSubmissionRequest request = new OrderSubmissionRequest(
                accountId, "AAPL", null, Order.Side.SELL, 5, new BigDecimal("160.00"));
        when(accountClient.getValidationData(eq(accountId), eq(instrument.getInstrumentId())))
                .thenReturn(new AccountValidationDto(true, true, new BigDecimal("10000.00"), 5L, "USD", "ACC-TEST-01"));
        when(tradeValidationService.validateTrade(any(), any(), any(), anyLong(), any()))
                .thenReturn(TradeValidationResult.accepted());
        when(accountClient.settleOrder(eq(accountId), any(SettlementRequest.class)))
                .thenThrow(refusal("Cannot settle sell: position does not exist"));

        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> orderSubmissionService.submitOrder(request));

        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, exception.getStatusCode());
        assertEquals("Settlement failed: Cannot settle sell: position does not exist", exception.getReason());
        assertEquals("Settlement failed: Cannot settle sell: position does not exist", storedOrder.getRejectionReason());
        assertOrderRejectedForSettlement();
    }

    // LLL-133
    @Test
    @DisplayName("Sell settlement fails without clamping insufficient holdings to zero")
    void testSellSettlement_InsufficientPosition_Throws() {
        OrderSubmissionRequest request = new OrderSubmissionRequest(
                accountId, "AAPL", null, Order.Side.SELL, 5, new BigDecimal("160.00"));
        when(accountClient.getValidationData(eq(accountId), eq(instrument.getInstrumentId())))
                .thenReturn(new AccountValidationDto(true, true, new BigDecimal("10000.00"), 5L, "USD", "ACC-TEST-01"));
        when(tradeValidationService.validateTrade(any(), any(), any(), anyLong(), any()))
                .thenReturn(TradeValidationResult.accepted());
        when(accountClient.settleOrder(eq(accountId), any(SettlementRequest.class)))
                .thenThrow(refusal("Cannot settle sell: insufficient position quantity"));

        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> orderSubmissionService.submitOrder(request));

        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, exception.getStatusCode());
        assertEquals("Settlement failed: Cannot settle sell: insufficient position quantity", exception.getReason());
        assertEquals("Settlement failed: Cannot settle sell: insufficient position quantity", storedOrder.getRejectionReason());
        assertOrderRejectedForSettlement();
    }

    /** account-app's answer when it refuses a settlement, as the RestClient raises it. */
    private static HttpClientErrorException refusal(String message) {
        HttpClientErrorException refusal = HttpClientErrorException.create(
                HttpStatus.BAD_REQUEST, "Bad Request", null, null, null);
        // account-app's JSON error body, as RestClient makes it readable.
        refusal.setBodyConvertFunction(type -> Map.of("error", "BAD_REQUEST", "message", message));
        return refusal;
    }

    private void assertOrderRejectedForSettlement() {
        assertEquals(Order.Status.REJECTED, storedOrder.getStatus());
        assertTrue(storedOrder.getRejectionReason().startsWith("Settlement failed: "));
        verify(positionMovementRepository, never()).save(any(PositionMovement.class));
    }

    @Test
    @DisplayName("Order and execution are committed before account-app settles, and the movement only after")
    void testCommitsBeforeSettling() {
        // account-app settles on its own database connection and its ledger rows reference the
        // order and execution by foreign key, so it can't settle rows this app hasn't committed.
        OrderSubmissionRequest request = new OrderSubmissionRequest(
                accountId, "AAPL", null, Order.Side.BUY, 10, new BigDecimal("150.00"));
        when(accountClient.getValidationData(eq(accountId), eq(instrument.getInstrumentId())))
                .thenReturn(validationDto);
        when(tradeValidationService.validateTrade(any(), any(), any(), anyLong(), any()))
                .thenReturn(TradeValidationResult.accepted());
        when(accountClient.settleOrder(eq(accountId), any(SettlementRequest.class)))
                .thenReturn(new SettlementResponse(UUID.randomUUID(), new BigDecimal("8500.00"), 10L, new BigDecimal("150.00")));

        orderSubmissionService.submitOrder(request);

        InOrder inOrder = inOrder(executionRepository, transactionManager, accountClient, positionMovementRepository);
        inOrder.verify(executionRepository).saveAndFlush(any(Execution.class));
        inOrder.verify(transactionManager).commit(any());
        inOrder.verify(accountClient).settleOrder(eq(accountId), any(SettlementRequest.class));
        inOrder.verify(positionMovementRepository).save(any(PositionMovement.class));
        inOrder.verify(transactionManager).commit(any());
    }

    @Test
    @DisplayName("Rejected order: SUBMITTED -> REJECTED, execution saved as REJECTED for audit, no settlement or movement")
    void testRejectedOrder_SavesAuditExecutionAndSkipsSettlement() {
        OrderSubmissionRequest request = new OrderSubmissionRequest(
                accountId, "AAPL", null, Order.Side.BUY, 1000, new BigDecimal("150.00"));

        when(accountClient.getValidationData(eq(accountId), eq(instrument.getInstrumentId())))
                .thenReturn(validationDto);
        when(tradeValidationService.validateTrade(any(), any(), any(), anyLong(), any()))
                .thenReturn(TradeValidationResult.rejected("Insufficient funds - order rejected"));

        OrderSubmissionResponse response = orderSubmissionService.submitOrder(request);

        assertNotNull(response);
        assertEquals("REJECTED", response.status());
        assertEquals("Insufficient funds - order rejected", response.rejectionReason());
        assertNotNull(response.rejectedAt());
        assertNull(response.filledAt());

        // Execution record must be saved as REJECTED with reason for audit retention
        assertNotNull(response.execution());
        assertEquals("REJECTED", response.execution().status());
        assertEquals("Insufficient funds - order rejected", response.execution().reason());
        assertNull(response.execution().fillQuantity());
        assertNull(response.execution().fillPrice());

        // Ensure NO settlement or position movements were created
        verify(accountClient, never()).settleOrder(any(), any());
        verify(positionMovementRepository, never()).save(any(PositionMovement.class));
    }

    @Test
    @DisplayName("Market BUY order without explicit price: resolves askPrice from CurrentQuoteService")
    void testMarketOrderBuy_ResolvesAskPriceFromQuote() {
        OrderSubmissionRequest request = new OrderSubmissionRequest(
                accountId, "AAPL", null, Order.Side.BUY, 10, null);

        QuoteSnapshot quote = new QuoteSnapshot(
                "AAPL", new BigDecimal("149.50"), 100L, new BigDecimal("150.50"), 100L,
                new BigDecimal("150.00"), 100L, "NASDAQ", OffsetDateTime.now());
        when(currentQuoteService.getCurrentQuote("AAPL")).thenReturn(quote);
        when(accountClient.getValidationData(eq(accountId), eq(instrument.getInstrumentId())))
                .thenReturn(validationDto);
        when(tradeValidationService.validateTrade(eq(validationDto), eq(instrument), eq(Order.Side.BUY), eq(10L), eq(new BigDecimal("150.5000"))))
                .thenReturn(TradeValidationResult.accepted());
        when(accountClient.settleOrder(eq(accountId), any(SettlementRequest.class)))
                .thenReturn(new SettlementResponse(UUID.randomUUID(), new BigDecimal("8495.00"), 10L, new BigDecimal("150.50")));

        OrderSubmissionResponse response = orderSubmissionService.submitOrder(request);

        assertNotNull(response);
        assertEquals("FILLED", response.status());
        assertEquals(new BigDecimal("150.5000"), response.execution().fillPrice());
        verify(currentQuoteService).getCurrentQuote("AAPL");
    }

    @Test
    @DisplayName("Market SELL order without explicit price: resolves bidPrice from CurrentQuoteService")
    void testMarketOrderSell_ResolvesBidPriceFromQuote() {
        OrderSubmissionRequest request = new OrderSubmissionRequest(
                accountId, "AAPL", null, Order.Side.SELL, 5, null);

        QuoteSnapshot quote = new QuoteSnapshot(
                "AAPL", new BigDecimal("149.50"), 100L, new BigDecimal("150.50"), 100L,
                new BigDecimal("150.00"), 100L, "NASDAQ", OffsetDateTime.now());
        when(currentQuoteService.getCurrentQuote("AAPL")).thenReturn(quote);

        AccountValidationDto sellValidation = new AccountValidationDto(true, true, new BigDecimal("10000.00"), 10L, "USD", "ACC-TEST-01");
        when(accountClient.getValidationData(eq(accountId), eq(instrument.getInstrumentId())))
                .thenReturn(sellValidation);
        when(tradeValidationService.validateTrade(eq(sellValidation), eq(instrument), eq(Order.Side.SELL), eq(5L), eq(new BigDecimal("149.5000"))))
                .thenReturn(TradeValidationResult.accepted());
        when(accountClient.settleOrder(eq(accountId), any(SettlementRequest.class)))
                .thenReturn(new SettlementResponse(UUID.randomUUID(), new BigDecimal("10747.50"), 5L, new BigDecimal("140.00")));

        OrderSubmissionResponse response = orderSubmissionService.submitOrder(request);

        assertNotNull(response);
        assertEquals("FILLED", response.status());
        assertEquals(new BigDecimal("149.5000"), response.execution().fillPrice());
        verify(currentQuoteService).getCurrentQuote("AAPL");
    }

    @Test
    @DisplayName("Market order when quote is unavailable: order and execution rejected for audit tracking")
    void testMarketOrder_QuoteUnavailable_RejectsOrder() {
        OrderSubmissionRequest request = new OrderSubmissionRequest(
                accountId, "AAPL", null, Order.Side.BUY, 10, null);

        when(accountClient.getValidationData(eq(accountId), eq(instrument.getInstrumentId())))
                .thenReturn(validationDto);
        when(currentQuoteService.getCurrentQuote("AAPL"))
                .thenThrow(new QuoteUnavailableException("Market data service offline"));

        OrderSubmissionResponse response = orderSubmissionService.submitOrder(request);

        assertNotNull(response);
        assertEquals("REJECTED", response.status());
        assertTrue(response.rejectionReason().contains("Market data service offline"));
        assertEquals("REJECTED", response.execution().status());
        assertEquals("Market data service offline", response.execution().reason());

        verify(orderRepository, times(2)).saveAndFlush(any(Order.class));
        verify(executionRepository).saveAndFlush(any(Execution.class));
        verify(accountClient, never()).settleOrder(any(), any());
    }

    private OrderSubmissionRequest acceptedBuy() {
        when(accountClient.getValidationData(eq(accountId), eq(instrument.getInstrumentId())))
                .thenReturn(validationDto);
        when(tradeValidationService.validateTrade(any(), any(), any(), anyLong(), any()))
                .thenReturn(TradeValidationResult.accepted());
        return new OrderSubmissionRequest(accountId, "AAPL", null, Order.Side.BUY, 10, new BigDecimal("150.00"));
    }

    @Test
    @DisplayName("Settlement timeout: order stays ACCEPTED for recovery instead of being rejected")
    void testSettlementTimeout_LeavesOrderAcceptedForRecovery() {
        OrderSubmissionRequest request = acceptedBuy();
        when(accountClient.settleOrder(eq(accountId), any(SettlementRequest.class)))
                .thenThrow(new ResourceAccessException("Read timed out"));

        OrderSubmissionResponse response = orderSubmissionService.submitOrder(request);

        assertEquals("ACCEPTED", response.status());
        assertEquals("FILLED", response.execution().status());
        assertNull(response.rejectionReason());
        assertNull(response.accountBalanceAfter());
        assertEquals(Order.Status.ACCEPTED, storedOrder.getStatus());
        verify(positionMovementRepository, never()).save(any(PositionMovement.class));
    }

    @Test
    @DisplayName("account-app 5xx: order stays ACCEPTED for recovery instead of being rejected")
    void testSettlementServerError_LeavesOrderAcceptedForRecovery() {
        OrderSubmissionRequest request = acceptedBuy();
        when(accountClient.settleOrder(eq(accountId), any(SettlementRequest.class)))
                .thenThrow(HttpServerErrorException.create(HttpStatus.SERVICE_UNAVAILABLE, "Unavailable", null, null, null));

        OrderSubmissionResponse response = orderSubmissionService.submitOrder(request);

        assertEquals("ACCEPTED", response.status());
        assertEquals(Order.Status.ACCEPTED, storedOrder.getStatus());
        assertNull(storedOrder.getRejectedAt());
    }

    @Test
    @DisplayName("Unexpected settle failure is not taken as a refusal: order stays ACCEPTED for recovery")
    void testUnexpectedSettlementFailure_LeavesOrderAcceptedForRecovery() {
        OrderSubmissionRequest request = acceptedBuy();
        when(accountClient.settleOrder(eq(accountId), any(SettlementRequest.class)))
                .thenThrow(new IllegalStateException("response could not be read"));

        OrderSubmissionResponse response = orderSubmissionService.submitOrder(request);

        assertEquals("ACCEPTED", response.status());
        assertEquals(Order.Status.ACCEPTED, storedOrder.getStatus());
    }

    @Test
    @DisplayName("Settled but the fill can't be recorded: order stays ACCEPTED for recovery and reports the new balance")
    void testRecordingFailsAfterSettling_LeavesOrderAcceptedForRecovery() {
        OrderSubmissionRequest request = acceptedBuy();
        when(accountClient.settleOrder(eq(accountId), any(SettlementRequest.class)))
                .thenReturn(new SettlementResponse(UUID.randomUUID(), new BigDecimal("8500.00"), 10L, new BigDecimal("150.00")));
        when(positionMovementRepository.save(any(PositionMovement.class)))
                .thenThrow(new IllegalStateException("database unavailable"));

        OrderSubmissionResponse response = orderSubmissionService.submitOrder(request);

        assertEquals("ACCEPTED", response.status());
        assertEquals(new BigDecimal("8500.00"), response.accountBalanceAfter());
        assertNull(response.filledAt());
    }

    @Test
    @DisplayName("Fill already finished by recovery: live submission doesn't record it again")
    void testFillAlreadyFinished_IsNotRecordedTwice() {
        OrderSubmissionRequest request = acceptedBuy();
        when(accountClient.settleOrder(eq(accountId), any(SettlementRequest.class)))
                .thenReturn(new SettlementResponse(UUID.randomUUID(), new BigDecimal("8500.00"), 10L, new BigDecimal("150.00")));
        when(positionMovementRepository.existsByOrderId(any())).thenReturn(true);

        OrderSubmissionResponse response = orderSubmissionService.submitOrder(request);

        assertEquals("FILLED", response.status());
        verify(positionMovementRepository, never()).save(any(PositionMovement.class));
    }
}
