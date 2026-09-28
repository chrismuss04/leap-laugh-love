package com.leap.leaplaughlove.order.history;

import com.leap.leaplaughlove.order.client.AccountClient;
import com.leap.leaplaughlove.order.client.SettlementRequest;
import com.leap.leaplaughlove.order.client.SettlementResponse;
import com.leap.leaplaughlove.order.execution.Execution;
import com.leap.leaplaughlove.order.execution.ExecutionRepository;
import com.leap.leaplaughlove.order.instrument.Instrument;
import com.leap.leaplaughlove.order.order.Order;
import com.leap.leaplaughlove.order.position.PositionMovement;
import com.leap.leaplaughlove.order.position.PositionMovementRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("FillRecorder Unit Tests")
class FillRecorderTest {

    @Mock
    private ExecutionRepository executionRepository;

    @Mock
    private PositionMovementRepository positionMovementRepository;

    @Mock
    private AccountClient accountClient;

    private FillRecorder fillRecorder;

    private Instrument instrument;
    private UUID accountId;
    private UUID orderId;
    private UUID instrumentId;

    @BeforeEach
    void setUp() {
        fillRecorder = new FillRecorder(executionRepository, positionMovementRepository, accountClient);
        instrumentId = UUID.randomUUID();
        instrument = new Instrument(instrumentId, "AAPL", "Apple Inc.", "EQUITY", "NASDAQ", "USD", true);
        accountId = UUID.randomUUID();
        orderId = UUID.randomUUID();
    }

    private Order createOrder(Order.Side side, long quantity) {
        return new Order(orderId, accountId, instrument, side, quantity,
                Order.Status.ACCEPTED, OffsetDateTime.now(), OffsetDateTime.now(), null, null, null);
    }

    @Test
    @DisplayName("recordExecution persists a FILLED execution with the given price and timestamp")
    void testRecordExecution() {
        Order order = createOrder(Order.Side.BUY, 10L);
        BigDecimal price = new BigDecimal("150.25");
        OffsetDateTime time = OffsetDateTime.now();

        Execution mockExecution = new Execution(UUID.randomUUID(), order, 10L, price,
                Execution.Status.FILLED, "Executed at market price", time);
        when(executionRepository.saveAndFlush(any(Execution.class))).thenReturn(mockExecution);

        Execution result = fillRecorder.recordExecution(order, price, time);

        assertNotNull(result);
        assertEquals(Execution.Status.FILLED, result.getStatus());
        assertEquals(price, result.getFillPrice());

        ArgumentCaptor<Execution> captor = ArgumentCaptor.forClass(Execution.class);
        verify(executionRepository).saveAndFlush(captor.capture());
        Execution captured = captor.getValue();
        assertEquals(order, captured.getOrder());
        assertEquals(10L, captured.getFillQuantity());
        assertEquals(price, captured.getFillPrice());
        assertEquals(Execution.Status.FILLED, captured.getStatus());
        assertEquals(time, captured.getExecutedAt());
    }

    @Test
    @DisplayName("settle without bearerToken calls accountClient.settleOrder")
    void testSettleWithoutBearerToken() {
        Order order = createOrder(Order.Side.BUY, 10L);
        UUID executionId = UUID.randomUUID();
        BigDecimal price = new BigDecimal("150.00");
        OffsetDateTime time = OffsetDateTime.now();
        Execution execution = new Execution(executionId, order, 10L, price,
                Execution.Status.FILLED, "Executed at market price", time);

        SettlementResponse expectedResponse = new SettlementResponse(
                UUID.randomUUID(), new BigDecimal("8500.00"), 10L, price);
        when(accountClient.settleOrder(eq(accountId), any(SettlementRequest.class)))
                .thenReturn(expectedResponse);

        SettlementResponse actualResponse = fillRecorder.settle(order, execution, null);

        assertEquals(expectedResponse, actualResponse);

        ArgumentCaptor<SettlementRequest> reqCaptor = ArgumentCaptor.forClass(SettlementRequest.class);
        verify(accountClient).settleOrder(eq(accountId), reqCaptor.capture());
        SettlementRequest req = reqCaptor.getValue();
        assertEquals(orderId, req.orderId());
        assertEquals(executionId, req.executionId());
        assertEquals(instrumentId, req.instrumentId());
        assertEquals("AAPL", req.symbol());
        assertEquals("BUY", req.side());
        assertEquals(10L, req.quantity());
        assertEquals(price, req.price());
    }

    @Test
    @DisplayName("settle with bearerToken calls accountClient.settleOrderAs")
    void testSettleWithBearerToken() {
        Order order = createOrder(Order.Side.SELL, 5L);
        UUID executionId = UUID.randomUUID();
        BigDecimal price = new BigDecimal("155.00");
        OffsetDateTime time = OffsetDateTime.now();
        Execution execution = new Execution(executionId, order, 5L, price,
                Execution.Status.FILLED, "Executed at market price", time);

        String token = "explicit-jwt-token";
        SettlementResponse expectedResponse = new SettlementResponse(
                UUID.randomUUID(), new BigDecimal("10775.00"), 0L, BigDecimal.ZERO);
        when(accountClient.settleOrderAs(eq(accountId), any(SettlementRequest.class), eq(token)))
                .thenReturn(expectedResponse);

        SettlementResponse actualResponse = fillRecorder.settle(order, execution, token);

        assertEquals(expectedResponse, actualResponse);
        verify(accountClient).settleOrderAs(eq(accountId), any(SettlementRequest.class), eq(token));
    }

    @Test
    @DisplayName("settle propagates exception when accountClient throws")
    void testSettleFailureThrowsException() {
        Order order = createOrder(Order.Side.BUY, 10L);
        Execution execution = new Execution(UUID.randomUUID(), order, 10L, new BigDecimal("150.00"),
                Execution.Status.FILLED, "Executed at market price", OffsetDateTime.now());

        when(accountClient.settleOrder(eq(accountId), any(SettlementRequest.class)))
                .thenThrow(new IllegalStateException("Settlement failed"));

        assertThrows(IllegalStateException.class, () -> fillRecorder.settle(order, execution, null));
    }

    @Test
    @DisplayName("recordPositionMovement for BUY creates positive quantity and positive tradeCost with BUY_FILL")
    void testRecordPositionMovementBuy() {
        Order order = createOrder(Order.Side.BUY, 10L);
        UUID executionId = UUID.randomUUID();
        BigDecimal price = new BigDecimal("150.50");
        OffsetDateTime time = OffsetDateTime.now();
        Execution execution = new Execution(executionId, order, 10L, price,
                Execution.Status.FILLED, "Executed at market price", time);

        fillRecorder.recordPositionMovement(order, execution);

        ArgumentCaptor<PositionMovement> captor = ArgumentCaptor.forClass(PositionMovement.class);
        verify(positionMovementRepository).save(captor.capture());
        PositionMovement movement = captor.getValue();

        assertEquals(accountId, movement.getAccountId());
        assertEquals(instrumentId, movement.getInstrumentId());
        assertEquals(orderId, movement.getOrderId());
        assertEquals(executionId, movement.getExecutionId());
        assertEquals("BUY_FILL", movement.getMovementType());
        assertEquals(10L, movement.getQuantityDelta());
        assertEquals(new BigDecimal("1505.00"), movement.getCostDelta());
        assertEquals(time, movement.getCreatedAt());
    }

    @Test
    @DisplayName("recordPositionMovement for SELL creates negative quantity and negative tradeCost with SELL_FILL")
    void testRecordPositionMovementSell() {
        Order order = createOrder(Order.Side.SELL, 4L);
        UUID executionId = UUID.randomUUID();
        BigDecimal price = new BigDecimal("200.00");
        OffsetDateTime time = OffsetDateTime.now();
        Execution execution = new Execution(executionId, order, 4L, price,
                Execution.Status.FILLED, "Executed at market price", time);

        fillRecorder.recordPositionMovement(order, execution);

        ArgumentCaptor<PositionMovement> captor = ArgumentCaptor.forClass(PositionMovement.class);
        verify(positionMovementRepository).save(captor.capture());
        PositionMovement movement = captor.getValue();

        assertEquals(accountId, movement.getAccountId());
        assertEquals(instrumentId, movement.getInstrumentId());
        assertEquals(orderId, movement.getOrderId());
        assertEquals(executionId, movement.getExecutionId());
        assertEquals("SELL_FILL", movement.getMovementType());
        assertEquals(-4L, movement.getQuantityDelta());
        assertEquals(new BigDecimal("-800.00"), movement.getCostDelta());
        assertEquals(time, movement.getCreatedAt());
    }
}

