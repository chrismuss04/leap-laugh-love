package com.leap.leaplaughlove.trading.order;

import com.leap.leaplaughlove.trading.account.Account;
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
class OrderHistoryServiceTest {

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private ExecutionRepository executionRepository;

    private OrderHistoryService orderHistoryService;

    private UUID clientId;
    private Account account;
    private Instrument aapl;
    private Instrument msft;
    private OffsetDateTime baseTime;

    @BeforeEach
    void setUp() {
        orderHistoryService = new OrderHistoryService(orderRepository, executionRepository);
        lenient().when(executionRepository.findByOrder_OrderIdInOrderByExecutedAtAsc(any())).thenReturn(List.of());

        clientId = UUID.randomUUID();
        account = new Account(UUID.randomUUID(), clientId, "ACCT-123", "ACTIVE", "USD", true, OffsetDateTime.now());
        aapl = new Instrument(UUID.randomUUID(), "AAPL", "Apple Inc.", "EQUITY");
        msft = new Instrument(UUID.randomUUID(), "MSFT", "Microsoft Corp.", "EQUITY");
        baseTime = OffsetDateTime.now();
    }

    @Test
    @DisplayName("maps Order entities to OrderHistoryItem projection, including fills")
    void testGetOrderHistorySuccess() {
        UUID orderId = UUID.randomUUID();
        Order order = new Order(
                orderId, account, aapl, Order.Side.BUY,
                new BigDecimal("10.5000"), Order.Status.FILLED, baseTime, baseTime.plusSeconds(5));

        when(orderRepository.findByAccount_ClientIdOrderBySubmittedAtDescOrderIdDesc(clientId, PageRequest.of(0, 20)))
                .thenReturn(new PageImpl<>(List.of(order)));

        Page<OrderHistoryItem> result = orderHistoryService.getOrderHistory(clientId, 0, 20);

        assertEquals(1, result.getTotalElements());
        OrderHistoryItem item = result.getContent().get(0);
        assertEquals(orderId, item.orderId());
        assertEquals("AAPL", item.symbol());
        assertEquals("BUY", item.side());
        assertEquals(new BigDecimal("10.5000"), item.quantity());
        assertEquals("FILLED", item.status());
        assertEquals(baseTime, item.submittedAt());
        assertEquals(baseTime.plusSeconds(5), item.filledAt());
        assertEquals(List.of(), item.executions());

        verify(orderRepository).findByAccount_ClientIdOrderBySubmittedAtDescOrderIdDesc(clientId, PageRequest.of(0, 20));
    }

    @Test
    @DisplayName("preserves repository ordering (newest first)")
    void testOrdersOrderedNewestFirst() {
        Order newer = new Order(
                UUID.randomUUID(), account, msft, Order.Side.SELL,
                new BigDecimal("50.0000"), Order.Status.FILLED,
                baseTime.plusMinutes(10), baseTime.plusMinutes(11));
        Order older = new Order(
                UUID.randomUUID(), account, aapl, Order.Side.BUY,
                new BigDecimal("100.0000"), Order.Status.FILLED, baseTime, baseTime.plusSeconds(2));

        when(orderRepository.findByAccount_ClientIdOrderBySubmittedAtDescOrderIdDesc(clientId, PageRequest.of(0, 20)))
                .thenReturn(new PageImpl<>(List.of(newer, older)));

        List<OrderHistoryItem> items = orderHistoryService.getOrderHistory(clientId, 0, 20).getContent();

        assertEquals(2, items.size());
        assertEquals("MSFT", items.get(0).symbol());
        assertEquals("AAPL", items.get(1).symbol());
    }

    @Test
    @DisplayName("carries pagination metadata through")
    void testPaginationMetadata() {
        when(orderRepository.findByAccount_ClientIdOrderBySubmittedAtDescOrderIdDesc(clientId, PageRequest.of(1, 5)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(1, 5), 12));

        Page<OrderHistoryItem> page = orderHistoryService.getOrderHistory(clientId, 1, 5);

        assertEquals(1, page.getNumber());
        assertEquals(5, page.getSize());
        assertEquals(12, page.getTotalElements());
        assertEquals(3, page.getTotalPages());
    }

    @Test
    @DisplayName("unfilled orders return null filledAt")
    void testUnfilledOrderHasNullFilledAt() {
        Order pending = new Order(
                UUID.randomUUID(), account, aapl, Order.Side.BUY,
                new BigDecimal("10.0000"), Order.Status.SUBMITTED, baseTime, null);

        when(orderRepository.findByAccount_ClientIdOrderBySubmittedAtDescOrderIdDesc(clientId, PageRequest.of(0, 20)))
                .thenReturn(new PageImpl<>(List.of(pending)));

        OrderHistoryItem item = orderHistoryService.getOrderHistory(clientId, 0, 20).getContent().get(0);

        assertEquals("SUBMITTED", item.status());
        assertNull(item.filledAt());
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
