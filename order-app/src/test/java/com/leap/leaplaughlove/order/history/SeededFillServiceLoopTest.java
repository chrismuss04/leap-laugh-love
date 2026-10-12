package com.leap.leaplaughlove.order.history;

import com.leap.leaplaughlove.common.security.JwtService;
import com.leap.leaplaughlove.order.account.Account;
import com.leap.leaplaughlove.order.account.AccountRepository;
import com.leap.leaplaughlove.order.events.OrderEventPublisher;
import com.leap.leaplaughlove.order.execution.Execution;
import com.leap.leaplaughlove.order.execution.ExecutionRepository;
import com.leap.leaplaughlove.order.instrument.Instrument;
import com.leap.leaplaughlove.order.order.Order;
import com.leap.leaplaughlove.order.order.OrderRepository;
import com.leap.leaplaughlove.order.position.PositionMovementRepository;
import com.leap.leaplaughlove.order.quote.PriceHistoryClient;
import com.leap.leaplaughlove.order.quote.PriceHistoryClient.CandleClose;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("SeededFillService retry loop and edge cases")
class SeededFillServiceLoopTest {

    private static final OffsetDateTime FILLED_AT = OffsetDateTime.of(2026, 9, 22, 14, 30, 30, 0, ZoneOffset.UTC);

    private final OrderRepository orderRepository = mock(OrderRepository.class);
    private final AccountRepository accountRepository = mock(AccountRepository.class);
    private final ExecutionRepository executionRepository = mock(ExecutionRepository.class);
    private final PositionMovementRepository movements = mock(PositionMovementRepository.class);
    private final FillRecorder fillRecorder = mock(FillRecorder.class);
    private final PriceHistoryClient priceHistoryClient = mock(PriceHistoryClient.class);
    private final JwtService jwtService = mock(JwtService.class);

    private final Account account = new Account(UUID.randomUUID(), UUID.randomUUID(), "ACC-1", "ACTIVE", "USD", true,
            FILLED_AT.minusDays(30));
    private final Instrument aapl = new Instrument(UUID.randomUUID(), "AAPL", "Apple Inc.", "EQUITY", "NASDAQ", "USD", true);

    private SeededFillService service(int maxAttempts) {
        return new SeededFillService(orderRepository, accountRepository, executionRepository, movements,
                fillRecorder, priceHistoryClient, jwtService,
                new TransactionTemplate(mock(PlatformTransactionManager.class)),
                mock(OrderEventPublisher.class), 0, maxAttempts);
    }

    private Order filledOrder(OffsetDateTime filledAt) {
        Order order = new Order(UUID.randomUUID(), account, aapl, Order.Side.BUY, 10L, Order.Status.FILLED,
                FILLED_AT.minusMinutes(2), FILLED_AT.minusMinutes(1), null, filledAt, null);
        when(orderRepository.findByIdForUpdate(order.getOrderId())).thenReturn(Optional.of(order));
        return order;
    }

    private void runLoop(SeededFillService service) throws Exception {
        Method loop = SeededFillService.class.getDeclaredMethod("bookUntilDone");
        loop.setAccessible(true);
        loop.invoke(service);
    }

    @BeforeEach
    void stubCollaborators() {
        when(accountRepository.findById(any())).thenReturn(Optional.of(account));
        when(jwtService.generateHistoryToken(any())).thenReturn("history-token");
        when(jwtService.generateSettlementToken(any())).thenReturn("settlement-token");
    }

    @Test
    @DisplayName("starts booking on a background thread once the application is ready")
    void startsBackgroundWorker() {
        when(orderRepository.findWithoutPositionMovementByStatus(Order.Status.FILLED)).thenReturn(List.of());

        service(1).start();

        verify(orderRepository, timeout(5000).atLeastOnce()).findWithoutPositionMovementByStatus(Order.Status.FILLED);
    }

    @Test
    @DisplayName("stops as soon as nothing is left to book")
    void stopsWhenNothingRemains() throws Exception {
        when(orderRepository.findWithoutPositionMovementByStatus(Order.Status.FILLED)).thenReturn(List.of());

        runLoop(service(5));

        verify(orderRepository, times(1)).findWithoutPositionMovementByStatus(Order.Status.FILLED);
    }

    @Test
    @DisplayName("retries after a failed pass and finishes once it succeeds")
    void retriesAfterFailure() throws Exception {
        when(orderRepository.findWithoutPositionMovementByStatus(Order.Status.FILLED))
                .thenThrow(new IllegalStateException("database blip"))
                .thenReturn(List.of());

        runLoop(service(3));

        verify(orderRepository, times(2)).findWithoutPositionMovementByStatus(Order.Status.FILLED);
    }

    @Test
    @DisplayName("gives up after the maximum number of attempts while fills stay unpriced")
    void givesUpAfterMaxAttempts() throws Exception {
        Order order = filledOrder(FILLED_AT);
        when(orderRepository.findWithoutPositionMovementByStatus(Order.Status.FILLED)).thenReturn(List.of(order));
        when(priceHistoryClient.fetchCloses(anyString(), any(), any(), anyInt(), anyString())).thenReturn(List.of());

        runLoop(service(2));

        verify(orderRepository, times(2)).findWithoutPositionMovementByStatus(Order.Status.FILLED);
        verify(fillRecorder, never()).recordExecution(any(), any(), any());
    }

    @Test
    @DisplayName("stops waiting when the worker is interrupted")
    void stopsWhenInterrupted() throws Exception {
        Order order = filledOrder(FILLED_AT);
        when(orderRepository.findWithoutPositionMovementByStatus(Order.Status.FILLED)).thenReturn(List.of(order));
        when(priceHistoryClient.fetchCloses(anyString(), any(), any(), anyInt(), anyString())).thenReturn(List.of());

        Thread.currentThread().interrupt();
        try {
            runLoop(service(5));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }

        verify(orderRepository, times(1)).findWithoutPositionMovementByStatus(Order.Status.FILLED);
    }

    @Test
    @DisplayName("leaves a fill with no fill time pending without asking market data")
    void fillWithoutTimeStaysPending() {
        Order order = filledOrder(null);
        when(orderRepository.findWithoutPositionMovementByStatus(Order.Status.FILLED)).thenReturn(List.of(order));

        assertEquals(1, service(1).bookPendingFills());

        verify(priceHistoryClient, never()).fetchCloses(anyString(), any(), any(), anyInt(), anyString());
    }

    @Test
    @DisplayName("fails the pass when a fill's account cannot be found")
    void missingAccountFailsThePass() {
        Order order = filledOrder(FILLED_AT);
        when(orderRepository.findWithoutPositionMovementByStatus(Order.Status.FILLED)).thenReturn(List.of(order));
        when(accountRepository.findById(any())).thenReturn(Optional.empty());

        IllegalStateException ex = org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> service(1).bookPendingFills());

        assertTrue(ex.getMessage().contains(order.getOrderId().toString()));
    }

    @Test
    @DisplayName("does not count a fill whose movement was recorded by someone else while settling")
    void movementRecordedMeanwhile() {
        Order order = filledOrder(FILLED_AT);
        when(orderRepository.findWithoutPositionMovementByStatus(Order.Status.FILLED)).thenReturn(List.of(order));
        when(priceHistoryClient.fetchCloses(anyString(), any(), any(), anyInt(), anyString()))
                .thenReturn(List.of(new CandleClose(FILLED_AT.minusSeconds(120), new BigDecimal("100"))));
        when(movements.existsByOrderId(order.getOrderId())).thenReturn(false, true);
        when(executionRepository.findFirstByOrder_OrderIdAndStatus(order.getOrderId(), Execution.Status.FILLED))
                .thenReturn(Optional.empty());
        when(fillRecorder.recordExecution(any(), any(), any())).thenReturn(
                new Execution(order, 10L, new BigDecimal("100"), Execution.Status.FILLED, "market", FILLED_AT));

        assertEquals(1, service(1).bookPendingFills());

        verify(fillRecorder, atLeastOnce()).settle(any(), any(), anyString());
        verify(fillRecorder, never()).recordPositionMovement(any(), any());
    }
}
