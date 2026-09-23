package com.leap.leaplaughlove.trading.order;

import com.leap.leaplaughlove.trading.account.Account;
import com.leap.leaplaughlove.trading.account.AccountAuthorizationService;
import com.leap.leaplaughlove.trading.ledger.CashLedgerEntry;
import com.leap.leaplaughlove.trading.ledger.CashLedgerRepository;
import com.leap.leaplaughlove.trading.position.Position;
import com.leap.leaplaughlove.trading.position.PositionMovement;
import com.leap.leaplaughlove.trading.position.PositionMovementRepository;
import com.leap.leaplaughlove.trading.position.PositionRepository;
import com.leap.leaplaughlove.trading.quote.CurrentQuoteService;
import com.leap.leaplaughlove.trading.quote.QuoteSnapshot;
import com.leap.leaplaughlove.trading.quote.QuoteUnavailableException;
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

    @Mock private AccountAuthorizationService accountAuthorizationService;
    @Mock private InstrumentRepository instrumentRepository;
    @Mock private OrderRepository orderRepository;
    @Mock private ExecutionRepository executionRepository;
    @Mock private CashLedgerRepository cashLedgerRepository;
    @Mock private PositionMovementRepository positionMovementRepository;
    @Mock private PositionRepository positionRepository;
    @Mock private TradeValidationService tradeValidationService;
    @Mock private CurrentQuoteService currentQuoteService;

    private OrderSubmissionService orderSubmissionService;

    private UUID clientId;
    private UUID accountId;
    private Account account;
    private Instrument instrument;

    @BeforeEach
    void setUp() {
        orderSubmissionService = new OrderSubmissionService(
                accountAuthorizationService,
                instrumentRepository,
                orderRepository,
                executionRepository,
                cashLedgerRepository,
                positionMovementRepository,
                positionRepository,
                tradeValidationService,
                currentQuoteService
        );

        clientId = UUID.randomUUID();
        accountId = UUID.randomUUID();
        account = new Account(accountId, clientId, "ACC-TEST-01", "ACTIVE", "USD", true, OffsetDateTime.now());
        instrument = new Instrument(UUID.randomUUID(), "AAPL", "Apple Inc.", "EQUITY", "NASDAQ", "USD", true);

        // LLL-133
        when(accountAuthorizationService.getAuthorizedTradingAccountForUpdate(accountId)).thenReturn(account);
        when(instrumentRepository.findBySymbol("AAPL")).thenReturn(Optional.of(instrument));
        when(orderRepository.saveAndFlush(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(executionRepository.saveAndFlush(any(Execution.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    @DisplayName("Successful BUY order: SUBMITTED -> ACCEPTED -> FILLED, updates execution, cash ledger, position movements, and holdings")
    void testSuccessfulBuyOrder() {
        OrderSubmissionRequest request = new OrderSubmissionRequest(
                accountId, "AAPL", null, Order.Side.BUY, 10, new BigDecimal("150.00"));

        when(tradeValidationService.validateTrade(eq(account), eq(instrument), eq(Order.Side.BUY), eq(10L), eq(new BigDecimal("150.0000"))))
                .thenReturn(TradeValidationResult.accepted());
        when(positionRepository.findByIdForUpdate(accountId, instrument.getInstrumentId())).thenReturn(Optional.empty());
        when(cashLedgerRepository.sumAmountByAccountIdAndCurrency(accountId, "USD"))
                .thenReturn(new BigDecimal("8500.00"));

        OrderSubmissionResponse response = orderSubmissionService.submitOrder(request);

        // LLL-133
        var settlementOrder = inOrder(accountAuthorizationService, tradeValidationService);
        settlementOrder.verify(accountAuthorizationService).getAuthorizedTradingAccountForUpdate(accountId);
        settlementOrder.verify(tradeValidationService).validateTrade(
                account, instrument, Order.Side.BUY, 10L, new BigDecimal("150.0000"));

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

        // Verify Cash Ledger entry saved with negative amount (buy settlement)
        ArgumentCaptor<CashLedgerEntry> cashCaptor = ArgumentCaptor.forClass(CashLedgerEntry.class);
        verify(cashLedgerRepository).save(cashCaptor.capture());
        CashLedgerEntry cashEntry = cashCaptor.getValue();
        assertEquals(new BigDecimal("-1500.00"), cashEntry.getAmount());
        assertEquals("BUY_SETTLEMENT", cashEntry.getEntryType());
        assertEquals(accountId, cashEntry.getAccountId());

        // Verify Position Movement saved with positive quantity delta
        ArgumentCaptor<PositionMovement> moveCaptor = ArgumentCaptor.forClass(PositionMovement.class);
        verify(positionMovementRepository).save(moveCaptor.capture());
        PositionMovement movement = moveCaptor.getValue();
        assertEquals(10L, movement.getQuantityDelta());
        assertEquals(new BigDecimal("1500.00"), movement.getCostDelta());
        assertEquals("BUY_FILL", movement.getMovementType());

        // Verify Position created with 10 shares
        ArgumentCaptor<Position> posCaptor = ArgumentCaptor.forClass(Position.class);
        verify(positionRepository).save(posCaptor.capture());
        Position position = posCaptor.getValue();
        assertEquals(10L, position.getQuantity());
        assertEquals(new BigDecimal("150.0000"), position.getAvgCost());
    }

    @Test
    @DisplayName("Successful SELL order: SUBMITTED -> ACCEPTED -> FILLED, updates cash ledger with positive amount and reduces position")
    void testSuccessfulSellOrder() {
        OrderSubmissionRequest request = new OrderSubmissionRequest(
                accountId, "AAPL", null, Order.Side.SELL, 5, new BigDecimal("160.00"));

        Position existingPosition = new Position(accountId, instrument.getInstrumentId(),
                20, new BigDecimal("140.000000"), OffsetDateTime.now());

        when(tradeValidationService.validateTrade(eq(account), eq(instrument), eq(Order.Side.SELL), eq(5L), eq(new BigDecimal("160.0000"))))
                .thenReturn(TradeValidationResult.accepted());
        when(positionRepository.findByIdForUpdate(accountId, instrument.getInstrumentId())).thenReturn(Optional.of(existingPosition));
        when(cashLedgerRepository.sumAmountByAccountIdAndCurrency(accountId, "USD"))
                .thenReturn(new BigDecimal("10800.00"));

        OrderSubmissionResponse response = orderSubmissionService.submitOrder(request);

        assertNotNull(response);
        assertEquals("FILLED", response.status());
        assertEquals("SELL", response.side());

        // LLL-133
        var settlementOrder = inOrder(accountAuthorizationService, tradeValidationService);
        settlementOrder.verify(accountAuthorizationService).getAuthorizedTradingAccountForUpdate(accountId);
        settlementOrder.verify(tradeValidationService).validateTrade(
                account, instrument, Order.Side.SELL, 5L, new BigDecimal("160.0000"));

        // Verify cash ledger entry has positive amount (sell settlement)
        ArgumentCaptor<CashLedgerEntry> cashCaptor = ArgumentCaptor.forClass(CashLedgerEntry.class);
        verify(cashLedgerRepository).save(cashCaptor.capture());
        CashLedgerEntry cashEntry = cashCaptor.getValue();
        assertEquals(new BigDecimal("800.00"), cashEntry.getAmount());
        assertEquals("SELL_SETTLEMENT", cashEntry.getEntryType());

        // Verify position movement has negative quantity delta
        ArgumentCaptor<PositionMovement> moveCaptor = ArgumentCaptor.forClass(PositionMovement.class);
        verify(positionMovementRepository).save(moveCaptor.capture());
        PositionMovement movement = moveCaptor.getValue();
        assertEquals(-5L, movement.getQuantityDelta());
        assertEquals("SELL_FILL", movement.getMovementType());

        // Verify position quantity reduced from 20 to 15
        ArgumentCaptor<Position> posCaptor = ArgumentCaptor.forClass(Position.class);
        verify(positionRepository).save(posCaptor.capture());
        Position position = posCaptor.getValue();
        assertEquals(15L, position.getQuantity());
    }

    // LLL-133
    @Test
    @DisplayName("Sell settlement fails when the validated position is missing")
    void testSellSettlement_MissingPosition_Throws() {
        OrderSubmissionRequest request = new OrderSubmissionRequest(
                accountId, "AAPL", null, Order.Side.SELL, 5, new BigDecimal("160.00"));
        when(tradeValidationService.validateTrade(any(), any(), any(), anyLong(), any()))
                .thenReturn(TradeValidationResult.accepted());
        when(positionRepository.findById(any(PositionId.class))).thenReturn(Optional.empty());

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> orderSubmissionService.submitOrder(request));

        assertEquals("Cannot settle sell: position does not exist", exception.getMessage());
        verify(positionRepository, never()).save(any(Position.class));
    }

    // LLL-133
    @Test
    @DisplayName("Sell settlement fails without clamping insufficient holdings to zero")
    void testSellSettlement_InsufficientPosition_Throws() {
        OrderSubmissionRequest request = new OrderSubmissionRequest(
                accountId, "AAPL", null, Order.Side.SELL, 5, new BigDecimal("160.00"));
        Position existingPosition = new Position(accountId, instrument.getInstrumentId(),
                3, new BigDecimal("140.000000"), OffsetDateTime.now());
        when(tradeValidationService.validateTrade(any(), any(), any(), anyLong(), any()))
                .thenReturn(TradeValidationResult.accepted());
        when(positionRepository.findById(any(PositionId.class))).thenReturn(Optional.of(existingPosition));

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> orderSubmissionService.submitOrder(request));

        assertEquals("Cannot settle sell: insufficient position quantity", exception.getMessage());
        assertEquals(3L, existingPosition.getQuantity());
        verify(positionRepository, never()).save(any(Position.class));
    }

    @Test
    @DisplayName("Rejected order: SUBMITTED -> REJECTED, execution saved as REJECTED for audit, no ledgers or positions touched")
    void testRejectedOrder_SavesAuditExecutionAndSkipsLedgers() {
        OrderSubmissionRequest request = new OrderSubmissionRequest(
                accountId, "AAPL", null, Order.Side.BUY, 1000, new BigDecimal("150.00"));

        when(tradeValidationService.validateTrade(any(), any(), any(), anyLong(), any()))
                .thenReturn(TradeValidationResult.rejected("Insufficient funds - order rejected"));
        when(cashLedgerRepository.sumAmountByAccountIdAndCurrency(accountId, "USD"))
                .thenReturn(new BigDecimal("100.00"));

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

        // Ensure NO cash ledger or position movements were created
        verify(cashLedgerRepository, never()).save(any(CashLedgerEntry.class));
        verify(positionMovementRepository, never()).save(any(PositionMovement.class));
        verify(positionRepository, never()).save(any(Position.class));
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

        when(tradeValidationService.validateTrade(eq(account), eq(instrument), eq(Order.Side.BUY), eq(10L), eq(new BigDecimal("150.5000"))))
                .thenReturn(TradeValidationResult.accepted());
        when(positionRepository.findByIdForUpdate(accountId, instrument.getInstrumentId())).thenReturn(Optional.empty());
        when(cashLedgerRepository.sumAmountByAccountIdAndCurrency(accountId, "USD"))
                .thenReturn(new BigDecimal("8495.00"));

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

        Position existingPosition = new Position(accountId, instrument.getInstrumentId(),
                10, new BigDecimal("140.000000"), OffsetDateTime.now());
        when(tradeValidationService.validateTrade(eq(account), eq(instrument), eq(Order.Side.SELL), eq(5L), eq(new BigDecimal("149.5000"))))
                .thenReturn(TradeValidationResult.accepted());
        when(positionRepository.findByIdForUpdate(accountId, instrument.getInstrumentId())).thenReturn(Optional.of(existingPosition));
        when(cashLedgerRepository.sumAmountByAccountIdAndCurrency(accountId, "USD"))
                .thenReturn(new BigDecimal("10747.50"));

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

        when(currentQuoteService.getCurrentQuote("AAPL"))
                .thenThrow(new QuoteUnavailableException("Market data service offline"));
        when(cashLedgerRepository.sumAmountByAccountIdAndCurrency(accountId, "USD"))
                .thenReturn(new BigDecimal("5000.00"));

        OrderSubmissionResponse response = orderSubmissionService.submitOrder(request);

        assertNotNull(response);
        assertEquals("REJECTED", response.status());
        assertTrue(response.rejectionReason().contains("Market data service offline"));
        assertEquals("REJECTED", response.execution().status());
        assertEquals("Market data service offline", response.execution().reason());

        verify(orderRepository, times(2)).saveAndFlush(any(Order.class));
        verify(executionRepository).saveAndFlush(any(Execution.class));
        verify(cashLedgerRepository, never()).save(any(CashLedgerEntry.class));
    }
}

