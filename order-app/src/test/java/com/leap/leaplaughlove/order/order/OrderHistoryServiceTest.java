package com.leap.leaplaughlove.order.order;

import com.leap.leaplaughlove.order.client.AccountClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("OrderHistoryService Tests")
class OrderHistoryServiceTest {

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private ExecutionRepository executionRepository;

    @Mock
    private AccountClient accountClient;

    private OrderHistoryService orderHistoryService;

    private UUID clientId;
    private UUID accountId;
    private Instrument aapl;
    private Instrument msft;
    private OffsetDateTime baseTime;

    @BeforeEach
    void setUp() {
        orderHistoryService = new OrderHistoryService(orderRepository, executionRepository, accountClient);
        lenient().when(executionRepository.findByOrder_OrderIdIn(any())).thenReturn(List.of());

        clientId = UUID.randomUUID();
        accountId = UUID.randomUUID();
        lenient().when(accountClient.getAccountIdsForClient()).thenReturn(List.of(accountId));

        aapl = new Instrument(UUID.randomUUID(), "AAPL", "Apple Inc.", "EQUITY", "NASDAQ", "USD", true);
        msft = new Instrument(UUID.randomUUID(), "MSFT", "Microsoft Corp.", "EQUITY", "NASDAQ", "USD", true);
        baseTime = OffsetDateTime.now();
    }

    @Test
    @DisplayName("maps Order entities to OrderHistoryItem projection, including execution fill details")
    void testGetOrderHistorySuccess() {
        UUID orderId = UUID.randomUUID();
        Order order = new Order(
                orderId, accountId, aapl, Order.Side.BUY,
                10L, Order.Status.FILLED, baseTime, baseTime.plusSeconds(1), null, baseTime.plusSeconds(5), null);

        Execution execution = new Execution(
                order, 10L, new BigDecimal("150.25"), Execution.Status.FILLED,
                "Executed at market price", baseTime.plusSeconds(5));

        when(orderRepository.findByAccountIdInOrderBySubmittedAtDescOrderIdDesc(List.of(accountId), PageRequest.of(0, 20)))
                .thenReturn(new PageImpl<>(List.of(order)));
        when(executionRepository.findByOrder_OrderIdIn(List.of(orderId)))
                .thenReturn(List.of(execution));

        Page<OrderHistoryItem> result = orderHistoryService.getOrderHistory(clientId, 0, 20);

        assertEquals(1, result.getTotalElements());
        OrderHistoryItem item = result.getContent().get(0);
        assertEquals(orderId, item.orderId());
        assertEquals("AAPL", item.symbol());
        assertEquals("BUY", item.side());
        assertEquals(10L, item.quantity());
        assertEquals("FILLED", item.status());
        assertEquals(baseTime, item.submittedAt());
        assertEquals(baseTime.plusSeconds(5), item.filledAt());

        assertNotNull(item.execution());
        assertEquals(10L, item.execution().quantity());
        assertEquals(new BigDecimal("150.25"), item.execution().price());
        assertEquals(baseTime.plusSeconds(5), item.execution().executedAt());
        assertEquals(1, item.executions().size());

        verify(orderRepository).findByAccountIdInOrderBySubmittedAtDescOrderIdDesc(List.of(accountId), PageRequest.of(0, 20));
    }

    @Test
    @DisplayName("an order filled by several executions reports every fill, oldest first")
    void testPartiallyFilledOrderReportsAllExecutions() {
        UUID orderId = UUID.randomUUID();
        Order order = new Order(
                orderId, accountId, aapl, Order.Side.BUY,
                30L, Order.Status.FILLED, baseTime, baseTime.plusSeconds(1), null, baseTime.plusSeconds(9), null);

        Execution firstFill = new Execution(
                order, 10L, new BigDecimal("150.25"), Execution.Status.FILLED,
                "Partial fill", baseTime.plusSeconds(5));
        Execution secondFill = new Execution(
                order, 20L, new BigDecimal("150.75"), Execution.Status.FILLED,
                "Partial fill", baseTime.plusSeconds(9));

        when(orderRepository.findByAccountIdInOrderBySubmittedAtDescOrderIdDesc(List.of(accountId), PageRequest.of(0, 20)))
                .thenReturn(new PageImpl<>(List.of(order)));
        when(executionRepository.findByOrder_OrderIdIn(List.of(orderId)))
                .thenReturn(List.of(secondFill, firstFill));

        OrderHistoryItem item = orderHistoryService.getOrderHistory(clientId, 0, 20).getContent().get(0);

        assertEquals(2, item.executions().size(), "both fills should be reported");
        assertEquals(10L, item.executions().get(0).quantity());
        assertEquals(new BigDecimal("150.25"), item.executions().get(0).price());
        assertEquals(20L, item.executions().get(1).quantity());
        assertEquals(new BigDecimal("150.75"), item.executions().get(1).price());
        assertEquals(30L, item.executions().stream().mapToLong(ExecutionItem::quantity).sum(),
                "reported fills should account for the whole order quantity");
        assertEquals(10L, item.execution().quantity(),
                "the legacy single-execution accessor exposes the first fill");
    }

    @Test
    @DisplayName("non-filled executions are excluded from the reported fills")
    void testNonFilledExecutionsExcluded() {
        UUID orderId = UUID.randomUUID();
        Order order = new Order(
                orderId, accountId, aapl, Order.Side.BUY,
                10L, Order.Status.FILLED, baseTime, baseTime.plusSeconds(1), null, baseTime.plusSeconds(5), null);

        Execution rejected = new Execution(
                order, 10L, new BigDecimal("150.00"), Execution.Status.REJECTED,
                "Insufficient funds", baseTime.plusSeconds(3));
        Execution filled = new Execution(
                order, 10L, new BigDecimal("150.25"), Execution.Status.FILLED,
                "Executed at market price", baseTime.plusSeconds(5));

        when(orderRepository.findByAccountIdInOrderBySubmittedAtDescOrderIdDesc(List.of(accountId), PageRequest.of(0, 20)))
                .thenReturn(new PageImpl<>(List.of(order)));
        when(executionRepository.findByOrder_OrderIdIn(List.of(orderId)))
                .thenReturn(List.of(rejected, filled));

        OrderHistoryItem item = orderHistoryService.getOrderHistory(clientId, 0, 20).getContent().get(0);

        assertEquals(1, item.executions().size());
        assertEquals(new BigDecimal("150.25"), item.executions().get(0).price());
    }

    @Test
    @DisplayName("rejected orders are returned in order history with REJECTED status and no fill execution")
    void testRejectedOrdersReturnedInHistory() {
        UUID orderId = UUID.randomUUID();
        Order rejectedOrder = new Order(
                orderId, accountId, aapl, Order.Side.BUY,
                1000000L, Order.Status.REJECTED, baseTime, null, baseTime.plusSeconds(1), null, "Insufficient funds");

        Execution rejectedExec = new Execution(
                rejectedOrder, null, null, Execution.Status.REJECTED,
                "Insufficient funds", baseTime.plusSeconds(1));

        when(orderRepository.findByAccountIdInOrderBySubmittedAtDescOrderIdDesc(List.of(accountId), PageRequest.of(0, 20)))
                .thenReturn(new PageImpl<>(List.of(rejectedOrder)));
        when(executionRepository.findByOrder_OrderIdIn(List.of(orderId)))
                .thenReturn(List.of(rejectedExec));

        Page<OrderHistoryItem> result = orderHistoryService.getOrderHistory(clientId, 0, 20);

        assertEquals(1, result.getTotalElements());
        OrderHistoryItem item = result.getContent().get(0);
        assertEquals("REJECTED", item.status());
        assertNull(item.filledAt());
        assertNull(item.execution());
        assertTrue(item.executions().isEmpty());
    }

    @Test
    @DisplayName("returns empty page when client has no accounts")
    void testNoAccountsReturnsEmptyPage() {
        when(accountClient.getAccountIdsForClient()).thenReturn(List.of());

        Page<OrderHistoryItem> result = orderHistoryService.getOrderHistory(clientId, 0, 20);

        assertEquals(0, result.getTotalElements());
        assertTrue(result.getContent().isEmpty());
    }

    @Test
    @DisplayName("preserves repository ordering (newest first)")
    void testOrdersOrderedNewestFirst() {
        Order newer = new Order(
                UUID.randomUUID(), accountId, msft, Order.Side.SELL,
                50L, Order.Status.FILLED,
                baseTime.plusMinutes(10), baseTime.plusMinutes(10).plusSeconds(1), null, baseTime.plusMinutes(11), null);
        Order older = new Order(
                UUID.randomUUID(), accountId, aapl, Order.Side.BUY,
                100L, Order.Status.FILLED,
                baseTime, baseTime.plusSeconds(1), null, baseTime.plusSeconds(2), null);

        when(orderRepository.findByAccountIdInOrderBySubmittedAtDescOrderIdDesc(List.of(accountId), PageRequest.of(0, 20)))
                .thenReturn(new PageImpl<>(List.of(newer, older)));

        List<OrderHistoryItem> items = orderHistoryService.getOrderHistory(clientId, 0, 20).getContent();

        assertEquals(2, items.size());
        assertEquals("MSFT", items.get(0).symbol());
        assertEquals("AAPL", items.get(1).symbol());
    }

    @Test
    @DisplayName("carries pagination metadata through")
    void testPaginationMetadata() {
        when(orderRepository.findByAccountIdInOrderBySubmittedAtDescOrderIdDesc(List.of(accountId), PageRequest.of(1, 5)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(1, 5), 12));

        Page<OrderHistoryItem> page = orderHistoryService.getOrderHistory(clientId, 1, 5);

        assertEquals(1, page.getNumber());
        assertEquals(5, page.getSize());
        assertEquals(12, page.getTotalElements());
        assertEquals(3, page.getTotalPages());
    }

    @Test
    @DisplayName("unfilled orders return null filledAt and null execution")
    void testUnfilledOrderHasNullFilledAt() {
        Order pending = new Order(
                UUID.randomUUID(), accountId, aapl, Order.Side.BUY,
                10L, Order.Status.SUBMITTED, baseTime, null, null, null, null);

        when(orderRepository.findByAccountIdInOrderBySubmittedAtDescOrderIdDesc(List.of(accountId), PageRequest.of(0, 20)))
                .thenReturn(new PageImpl<>(List.of(pending)));

        OrderHistoryItem item = orderHistoryService.getOrderHistory(clientId, 0, 20).getContent().get(0);

        assertEquals("SUBMITTED", item.status());
        assertNull(item.filledAt());
        assertNull(item.execution());
        assertTrue(item.executions().isEmpty());
    }

    @Test
    @DisplayName("throws IllegalArgumentException when page is negative")
    void testNegativePageThrows() {
        assertThrows(IllegalArgumentException.class, () -> orderHistoryService.getOrderHistory(clientId, -1, 20));
    }

    @Test
    @DisplayName("throws IllegalArgumentException when size exceeds max")
    void testExcessiveSizeThrows() {
        assertThrows(IllegalArgumentException.class, () -> orderHistoryService.getOrderHistory(clientId, 0, 101));
    }
}
