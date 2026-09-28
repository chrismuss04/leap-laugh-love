package com.leap.leaplaughlove.order.history;

import com.leap.leaplaughlove.common.security.JwtService;
import com.leap.leaplaughlove.order.account.Account;
import com.leap.leaplaughlove.order.account.AccountRepository;
import com.leap.leaplaughlove.order.client.AccountClient;
import com.leap.leaplaughlove.order.client.SettlementRequest;
import com.leap.leaplaughlove.order.client.SettlementResponse;
import com.leap.leaplaughlove.order.execution.Execution;
import com.leap.leaplaughlove.order.execution.ExecutionRepository;
import com.leap.leaplaughlove.order.instrument.Instrument;
import com.leap.leaplaughlove.order.order.Order;
import com.leap.leaplaughlove.order.order.OrderRepository;
import com.leap.leaplaughlove.order.position.PositionMovement;
import com.leap.leaplaughlove.order.position.PositionMovementRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("PendingFillRecovery Tests")
class PendingFillRecoveryTest {

    @Mock private OrderRepository orderRepository;
    @Mock private ExecutionRepository executionRepository;
    @Mock private AccountRepository accountRepository;
    @Mock private PositionMovementRepository positionMovementRepository;
    @Mock private AccountClient accountClient;
    @Mock private JwtService jwtService;

    private PendingFillRecovery recovery;
    private Order order;
    private Execution execution;
    private Account account;

    @BeforeEach
    void setUp() {
        FillRecorder fillRecorder = new FillRecorder(executionRepository, positionMovementRepository, accountClient);
        recovery = new PendingFillRecovery(orderRepository, executionRepository, accountRepository, fillRecorder, jwtService,
                new TransactionTemplate(mock(PlatformTransactionManager.class)), 30);

        account = new Account(UUID.randomUUID(), UUID.randomUUID(), "ACC-TEST-01", "ACTIVE", "USD", true,
                OffsetDateTime.now().minusDays(30));
        Instrument aapl = new Instrument(UUID.randomUUID(), "AAPL", "Apple Inc.", "EQUITY", "NASDAQ", "USD", true);
        OffsetDateTime acceptedAt = OffsetDateTime.now().minusMinutes(5);
        order = new Order(UUID.randomUUID(), account, aapl, Order.Side.BUY, 10L, Order.Status.ACCEPTED,
                acceptedAt, acceptedAt, null, null, null);
        execution = new Execution(order, 10L, new BigDecimal("150.0000"), Execution.Status.FILLED,
                "Executed at market price", acceptedAt);

        when(jwtService.generateToken(any(), any())).thenReturn("token");
        when(accountRepository.findById(account.getAccountId())).thenReturn(Optional.of(account));
        when(orderRepository.findAcceptedWithFilledExecution(any())).thenReturn(List.of(order));
        when(orderRepository.findByIdForUpdate(order.getOrderId())).thenReturn(Optional.of(order));
        when(executionRepository.findFirstByOrder_OrderIdAndStatus(order.getOrderId(), Execution.Status.FILLED))
                .thenReturn(Optional.of(execution));
    }

    @Test
    @DisplayName("settles a stuck fill as its owner, writes its position movement, and marks it FILLED")
    void settlesAndFinishesStuckFill() {
        when(accountClient.settleOrderAs(eq(order.getAccountId()), any(SettlementRequest.class), eq("token")))
                .thenReturn(new SettlementResponse(UUID.randomUUID(), new BigDecimal("8500.00"), 10L, new BigDecimal("150.00")));

        assertEquals(0, recovery.recoverPendingFills());

        assertEquals(Order.Status.FILLED, order.getStatus());
        assertEquals(execution.getExecutedAt(), order.getFilledAt());
        ArgumentCaptor<PositionMovement> movement = ArgumentCaptor.forClass(PositionMovement.class);
        verify(positionMovementRepository).save(movement.capture());
        assertEquals(10L, movement.getValue().getQuantityDelta());
        verify(jwtService).generateToken(account.getClientId(), null);
    }

    @Test
    @DisplayName("only looks at fills accepted before the grace period, so in-flight submissions are left alone")
    void skipsFillsInsideGracePeriod() {
        when(accountClient.settleOrderAs(any(), any(), any()))
                .thenReturn(new SettlementResponse(UUID.randomUUID(), BigDecimal.ZERO, 10L, BigDecimal.ONE));
        OffsetDateTime before = OffsetDateTime.now();

        recovery.recoverPendingFills();

        ArgumentCaptor<OffsetDateTime> cutoff = ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(orderRepository).findAcceptedWithFilledExecution(cutoff.capture());
        assertTrue(!cutoff.getValue().isBefore(before.minusSeconds(30)));
        assertTrue(!cutoff.getValue().isAfter(OffsetDateTime.now().minusSeconds(30)));
    }

    @Test
    @DisplayName("leaves the order ACCEPTED when account-app is still unreachable, to retry next run")
    void leavesOrderPendingWhileAccountAppUnreachable() {
        when(accountClient.settleOrderAs(any(), any(), any())).thenThrow(new ResourceAccessException("Connection refused"));

        assertEquals(1, recovery.recoverPendingFills());

        assertEquals(Order.Status.ACCEPTED, order.getStatus());
        verify(positionMovementRepository, never()).save(any());
    }

    @Test
    @DisplayName("marks the order REJECTED when account-app refuses the settlement outright")
    void rejectsOrderRefusedByAccountApp() {
        when(accountClient.settleOrderAs(any(), any(), any())).thenThrow(
                HttpClientErrorException.create(HttpStatus.BAD_REQUEST, "Bad Request", null, null, null));

        assertEquals(0, recovery.recoverPendingFills());

        assertEquals(Order.Status.REJECTED, order.getStatus());
        assertTrue(order.getRejectionReason().startsWith("Settlement failed: "));
        verify(positionMovementRepository, never()).save(any());
    }

    @Test
    @DisplayName("does nothing to a fill live submission finished between the query and the lock")
    void skipsFillFinishedMeanwhile() {
        when(accountClient.settleOrderAs(any(), any(), any()))
                .thenReturn(new SettlementResponse(UUID.randomUUID(), BigDecimal.ZERO, 10L, BigDecimal.ONE));
        when(positionMovementRepository.existsByOrderId(order.getOrderId())).thenReturn(true);

        assertEquals(0, recovery.recoverPendingFills());

        verify(positionMovementRepository, never()).save(any());
    }
}
