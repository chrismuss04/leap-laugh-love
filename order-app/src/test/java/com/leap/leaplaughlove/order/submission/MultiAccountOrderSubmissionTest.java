package com.leap.leaplaughlove.order.submission;

import com.leap.leaplaughlove.order.client.AccountClient;
import com.leap.leaplaughlove.order.client.AccountValidationDto;
import com.leap.leaplaughlove.order.client.SettlementRequest;
import com.leap.leaplaughlove.order.client.SettlementResponse;
import com.leap.leaplaughlove.order.execution.Execution;
import com.leap.leaplaughlove.order.execution.ExecutionRepository;
import com.leap.leaplaughlove.order.events.OrderEventPublisher;
import com.leap.leaplaughlove.order.history.FillRecorder;
import com.leap.leaplaughlove.order.instrument.Instrument;
import com.leap.leaplaughlove.order.instrument.InstrumentRepository;
import com.leap.leaplaughlove.order.order.Order;
import com.leap.leaplaughlove.order.order.OrderRepository;
import com.leap.leaplaughlove.order.position.PositionMovement;
import com.leap.leaplaughlove.order.position.PositionMovementRepository;
import com.leap.leaplaughlove.order.quote.CurrentQuoteService;
import com.leap.leaplaughlove.order.quote.QuoteSnapshot;
import com.leap.leaplaughlove.order.validation.TradeValidationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.eq;

/**
 * One client owns two accounts. Each order is validated, stored and settled against the account
 * it names, with the real trade validation and fill recording, so nothing one account holds can
 * leak into another account's order.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Order submission across a client's multiple accounts")
class MultiAccountOrderSubmissionTest {

    @Mock private AccountClient accountClient;
    @Mock private InstrumentRepository instrumentRepository;
    @Mock private OrderRepository orderRepository;
    @Mock private ExecutionRepository executionRepository;
    @Mock private PositionMovementRepository positionMovementRepository;
    @Mock private CurrentQuoteService currentQuoteService;
    @Mock private PlatformTransactionManager transactionManager;
    @Mock private OrderEventPublisher orderEventPublisher;

    private OrderSubmissionService service;

    private final UUID accountA = UUID.randomUUID();
    private final UUID accountB = UUID.randomUUID();
    private Instrument instrument;
    private final Map<UUID, Order> orders = new LinkedHashMap<>();

    @BeforeEach
    void setUp() {
        FillRecorder fillRecorder = new FillRecorder(executionRepository, positionMovementRepository, accountClient);
        service = new OrderSubmissionService(accountClient, instrumentRepository, orderRepository,
                executionRepository, fillRecorder, new TradeValidationService(), currentQuoteService,
                new TransactionTemplate(transactionManager), orderEventPublisher);

        instrument = new Instrument(UUID.randomUUID(), "AAPL", "Apple Inc.", "EQUITY", "NASDAQ", "USD", true);
        when(instrumentRepository.findBySymbol("AAPL")).thenReturn(Optional.of(instrument));
        // Orders fill at the live quote; a flat $150 market keeps buy and sell prices alike.
        BigDecimal price = new BigDecimal("150.00");
        lenient().when(currentQuoteService.getCurrentQuote("AAPL")).thenReturn(new QuoteSnapshot(
                "AAPL", price, 100L, price, 100L, price, 100L, "NASDAQ", OffsetDateTime.now()));

        // Outside a database nothing assigns ids, and two orders can only be told apart by theirs.
        when(orderRepository.saveAndFlush(any(Order.class))).thenAnswer(invocation -> {
            Order order = invocation.getArgument(0);
            if (order.getOrderId() == null) {
                ReflectionTestUtils.setField(order, "orderId", UUID.randomUUID());
            }
            orders.put(order.getOrderId(), order);
            return order;
        });
        lenient().when(orderRepository.findByIdForUpdate(any()))
                .thenAnswer(invocation -> Optional.ofNullable(orders.get(invocation.<UUID>getArgument(0))));
        when(executionRepository.saveAndFlush(any(Execution.class))).thenAnswer(invocation -> {
            Execution execution = invocation.getArgument(0);
            if (execution.getExecutionId() == null) {
                ReflectionTestUtils.setField(execution, "executionId", UUID.randomUUID());
            }
            return execution;
        });

        // A holds cash and no shares; B holds shares and little cash.
        account(accountA, "ACC-A", "10000.00", 0L);
        account(accountB, "ACC-B", "500.00", 20L);
    }

    private void account(UUID accountId, String number, String cash, long holdings) {
        lenient().when(accountClient.getValidationData(eq(accountId), any()))
                .thenReturn(new AccountValidationDto(true, true, new BigDecimal(cash), holdings, "USD", number, null));
    }

    private void settles(UUID accountId, String balanceAfter) {
        lenient().when(accountClient.settleOrder(eq(accountId), any(SettlementRequest.class)))
                .thenReturn(new SettlementResponse(UUID.randomUUID(), new BigDecimal(balanceAfter), 0L, BigDecimal.ZERO));
    }

    private OrderSubmissionRequest order(UUID accountId, Order.Side side, long quantity) {
        return new OrderSubmissionRequest(accountId, "AAPL", null, side, quantity, new BigDecimal("150.00"), null);
    }

    private static HttpClientErrorException refusal(String message) {
        HttpClientErrorException refusal = HttpClientErrorException.create(
                HttpStatus.BAD_REQUEST, "Bad Request", null, null, null);
        refusal.setBodyConvertFunction(type -> Map.of("error", "BAD_REQUEST", "message", message));
        return refusal;
    }

    private List<PositionMovement> savedMovements(int expected) {
        ArgumentCaptor<PositionMovement> captor = ArgumentCaptor.forClass(PositionMovement.class);
        verify(positionMovementRepository, times(expected)).save(captor.capture());
        return captor.getAllValues();
    }

    private Order storedOrderFor(UUID orderId) {
        return orders.get(orderId);
    }

    @Test
    @DisplayName("an order in one account is validated, stored and settled against that account only")
    void orderTouchesOnlyItsOwnAccount() {
        settles(accountA, "8500.00");

        OrderSubmissionResponse response = service.submitOrder(order(accountA, Order.Side.BUY, 10));

        assertEquals("FILLED", response.status());
        assertEquals(accountA, response.accountId());
        assertEquals("ACC-A", response.accountNumber());
        assertEquals(accountA, storedOrderFor(response.orderId()).getAccountId());
        verify(accountClient).settleOrder(eq(accountA), any(SettlementRequest.class));
        verify(accountClient, never()).getValidationData(eq(accountB), any());
        verify(accountClient, never()).settleOrder(eq(accountB), any(SettlementRequest.class));
        assertEquals(accountA, savedMovements(1).get(0).getAccountId());
    }

    @Test
    @DisplayName("the same BUY is accepted in the account with the cash and rejected in the one without")
    void buyUsesEachAccountsOwnCash() {
        settles(accountA, "8500.00");

        // 10 shares at $150 costs $1,500: B only holds $500.
        OrderSubmissionResponse rejected = service.submitOrder(order(accountB, Order.Side.BUY, 10));
        OrderSubmissionResponse filled = service.submitOrder(order(accountA, Order.Side.BUY, 10));

        assertEquals("REJECTED", rejected.status());
        assertEquals("Insufficient funds - order rejected", rejected.rejectionReason());
        assertEquals("FILLED", filled.status());
        verify(accountClient, never()).settleOrder(eq(accountB), any(SettlementRequest.class));
        verify(accountClient, times(1)).settleOrder(eq(accountA), any(SettlementRequest.class));
        List<PositionMovement> movements = savedMovements(1);
        assertEquals(accountA, movements.get(0).getAccountId());
    }

    @Test
    @DisplayName("the same SELL is accepted in the account holding the shares and rejected in the one without")
    void sellUsesEachAccountsOwnHoldings() {
        settles(accountB, "1250.00");

        OrderSubmissionResponse rejected = service.submitOrder(order(accountA, Order.Side.SELL, 5));
        OrderSubmissionResponse filled = service.submitOrder(order(accountB, Order.Side.SELL, 5));

        assertEquals("REJECTED", rejected.status());
        assertEquals("Insufficient position quantity - order rejected", rejected.rejectionReason());
        assertEquals("FILLED", filled.status());
        verify(accountClient, never()).settleOrder(eq(accountA), any(SettlementRequest.class));
        assertEquals(accountB, savedMovements(1).get(0).getAccountId());
    }

    @Test
    @DisplayName("fills in two accounts each settle against their own account, side and quantity")
    void fillsInBothAccountsSettleSeparately() {
        settles(accountA, "8500.00");
        settles(accountB, "1250.00");

        OrderSubmissionResponse buyInA = service.submitOrder(order(accountA, Order.Side.BUY, 10));
        OrderSubmissionResponse sellInB = service.submitOrder(order(accountB, Order.Side.SELL, 5));

        assertEquals("FILLED", buyInA.status());
        assertEquals("FILLED", sellInB.status());
        assertNotEquals(buyInA.orderId(), sellInB.orderId());

        ArgumentCaptor<SettlementRequest> requestA = ArgumentCaptor.forClass(SettlementRequest.class);
        ArgumentCaptor<SettlementRequest> requestB = ArgumentCaptor.forClass(SettlementRequest.class);
        verify(accountClient, times(1)).settleOrder(eq(accountA), requestA.capture());
        verify(accountClient, times(1)).settleOrder(eq(accountB), requestB.capture());
        assertEquals(buyInA.orderId(), requestA.getValue().orderId());
        assertEquals("BUY", requestA.getValue().side());
        assertEquals(10L, requestA.getValue().quantity());
        assertEquals(sellInB.orderId(), requestB.getValue().orderId());
        assertEquals("SELL", requestB.getValue().side());
        assertEquals(5L, requestB.getValue().quantity());

        List<PositionMovement> movements = savedMovements(2);
        PositionMovement movementA = movements.stream().filter(m -> m.getAccountId().equals(accountA)).findFirst().orElseThrow();
        PositionMovement movementB = movements.stream().filter(m -> m.getAccountId().equals(accountB)).findFirst().orElseThrow();
        assertEquals(buyInA.orderId(), movementA.getOrderId());
        assertEquals(10L, movementA.getQuantityDelta());
        assertEquals(0, new BigDecimal("1500.00").compareTo(movementA.getCostDelta()));
        assertEquals(sellInB.orderId(), movementB.getOrderId());
        assertEquals(-5L, movementB.getQuantityDelta());
        assertEquals(0, new BigDecimal("-750.00").compareTo(movementB.getCostDelta()));
    }

    @Test
    @DisplayName("each response reports its own account's number and balance after the fill")
    void responsesReportEachAccountsOwnBalance() {
        settles(accountA, "8500.00");
        settles(accountB, "1250.00");

        OrderSubmissionResponse buyInA = service.submitOrder(order(accountA, Order.Side.BUY, 10));
        OrderSubmissionResponse sellInB = service.submitOrder(order(accountB, Order.Side.SELL, 5));

        assertEquals("ACC-A", buyInA.accountNumber());
        assertEquals(new BigDecimal("8500.00"), buyInA.accountBalanceAfter());
        assertEquals("ACC-B", sellInB.accountNumber());
        assertEquals(new BigDecimal("1250.00"), sellInB.accountBalanceAfter());
    }

    @Test
    @DisplayName("a settlement refused in one account rejects only that account's order")
    void refusedSettlementInOneAccountLeavesTheOtherFilled() {
        settles(accountA, "8500.00");
        when(accountClient.settleOrder(eq(accountB), any(SettlementRequest.class)))
                .thenThrow(refusal("Cannot settle sell: insufficient position quantity"));

        ResponseStatusException refused = assertThrows(ResponseStatusException.class,
                () -> service.submitOrder(order(accountB, Order.Side.SELL, 5)));
        OrderSubmissionResponse filled = service.submitOrder(order(accountA, Order.Side.BUY, 10));

        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, refused.getStatusCode());
        assertEquals("FILLED", filled.status());
        List<Order> stored = List.copyOf(orders.values());
        Order orderB = stored.stream().filter(o -> o.getAccountId().equals(accountB)).findFirst().orElseThrow();
        Order orderA = stored.stream().filter(o -> o.getAccountId().equals(accountA)).findFirst().orElseThrow();
        assertEquals(Order.Status.REJECTED, orderB.getStatus());
        assertEquals(Order.Status.FILLED, orderA.getStatus());
        assertEquals(accountA, savedMovements(1).get(0).getAccountId());
    }
}
