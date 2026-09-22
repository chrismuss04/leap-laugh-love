package com.leap.leaplaughlove.order.order;

import com.leap.leaplaughlove.order.client.AccountClient;
import com.leap.leaplaughlove.order.client.AccountValidationDto;
import com.leap.leaplaughlove.order.client.SettlementRequest;
import com.leap.leaplaughlove.order.client.SettlementResponse;
import com.leap.leaplaughlove.order.quote.CurrentQuoteService;
import com.leap.leaplaughlove.order.quote.QuoteSnapshot;
import com.leap.leaplaughlove.order.quote.QuoteUnavailableException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
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

    private OrderSubmissionService orderSubmissionService;

    private UUID accountId;
    private Instrument instrument;
    private AccountValidationDto validationDto;

    @BeforeEach
    void setUp() {
        orderSubmissionService = new OrderSubmissionService(
                accountClient,
                instrumentRepository,
                orderRepository,
                executionRepository,
                positionMovementRepository,
                tradeValidationService,
                currentQuoteService
        );

        accountId = UUID.randomUUID();
        instrument = new Instrument(UUID.randomUUID(), "AAPL", "Apple Inc.", "EQUITY", "NASDAQ", "USD", true);
        validationDto = new AccountValidationDto(true, true, new BigDecimal("10000.00"), 0L, "USD", "ACC-TEST-01");

        when(instrumentRepository.findBySymbol("AAPL")).thenReturn(Optional.of(instrument));
        when(orderRepository.saveAndFlush(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));
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
}
