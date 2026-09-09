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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("Story PB-07 Acceptance Criteria Tests")
class OrderHistoryStoryTest {

    @Mock
    private OrderRepository orderRepository;

    private OrderHistoryService orderHistoryService;

    private UUID aliceClientId;
    private Account aliceAccount;
    private Instrument aapl;
    private Instrument msft;
    private OffsetDateTime baseTime;

    @BeforeEach
    void setUp() {
        orderHistoryService = new OrderHistoryService(orderRepository);

        aliceClientId = UUID.randomUUID();
        aliceAccount = new Account(UUID.randomUUID(), aliceClientId, "ACC-ALICE-1", "ACTIVE", "USD", true, OffsetDateTime.now());

        aapl = new Instrument(UUID.randomUUID(), "AAPL", "Apple Inc.", "EQUITY");
        msft = new Instrument(UUID.randomUUID(), "MSFT", "Microsoft Corp.", "EQUITY");

        baseTime = OffsetDateTime.now();
    }

    @Test
    @DisplayName("AC1: Returns only orders belonging to the requested client")
    void testAC1_OnlyRequestedClientOrdersReturned() {
        Order aliceOrder = new Order(
                UUID.randomUUID(), aliceAccount, aapl, Order.Side.BUY, Order.Type.MARKET,
                new BigDecimal("100.0000"), null, Order.Status.FILLED, baseTime, baseTime.plusSeconds(2));

        when(orderRepository.findByAccount_ClientIdOrderBySubmittedAtDescOrderIdDesc(eq(aliceClientId), eq(PageRequest.of(0, 20))))
                .thenReturn(new PageImpl<>(List.of(aliceOrder)));

        Page<OrderHistoryItem> page = orderHistoryService.getOrderHistory(aliceClientId, 0, 20);

        assertEquals(1, page.getTotalElements());
        verify(orderRepository).findByAccount_ClientIdOrderBySubmittedAtDescOrderIdDesc(eq(aliceClientId), eq(PageRequest.of(0, 20)));
    }

    @Test
    @DisplayName("AC2: Orders ordered newest to oldest by submittedAt")
    void testAC2_OrderedNewestToOldest() {
        Order newerOrder = new Order(
                UUID.randomUUID(), aliceAccount, msft, Order.Side.SELL, Order.Type.LIMIT,
                new BigDecimal("50.0000"), new BigDecimal("320.0000"), Order.Status.FILLED,
                baseTime.plusMinutes(10), baseTime.plusMinutes(11));

        Order olderOrder = new Order(
                UUID.randomUUID(), aliceAccount, aapl, Order.Side.BUY, Order.Type.MARKET,
                new BigDecimal("100.0000"), null, Order.Status.FILLED,
                baseTime, baseTime.plusSeconds(2));

        when(orderRepository.findByAccount_ClientIdOrderBySubmittedAtDescOrderIdDesc(eq(aliceClientId), eq(PageRequest.of(0, 20))))
                .thenReturn(new PageImpl<>(List.of(newerOrder, olderOrder)));

        Page<OrderHistoryItem> page = orderHistoryService.getOrderHistory(aliceClientId, 0, 20);

        List<OrderHistoryItem> items = page.getContent();
        assertEquals(2, items.size());
        assertTrue(items.get(0).submittedAt().isAfter(items.get(1).submittedAt()));
        assertEquals("MSFT", items.get(0).symbol());
        assertEquals("AAPL", items.get(1).symbol());
    }

    @Test
    @DisplayName("AC3: History items contain required fields")
    void testAC3_RequiredFieldsPresent() {
        UUID orderId = UUID.randomUUID();
        OffsetDateTime submitted = baseTime;
        OffsetDateTime filled = baseTime.plusSeconds(5);

        Order order = new Order(
                orderId, aliceAccount, aapl, Order.Side.BUY, Order.Type.MARKET,
                new BigDecimal("25.5000"), null, Order.Status.FILLED, submitted, filled);

        when(orderRepository.findByAccount_ClientIdOrderBySubmittedAtDescOrderIdDesc(eq(aliceClientId), eq(PageRequest.of(0, 20))))
                .thenReturn(new PageImpl<>(List.of(order)));

        Page<OrderHistoryItem> page = orderHistoryService.getOrderHistory(aliceClientId, 0, 20);

        OrderHistoryItem item = page.getContent().get(0);
        assertEquals(orderId, item.orderId());
        assertEquals("AAPL", item.symbol());
        assertEquals("BUY", item.side());
        assertEquals(new BigDecimal("25.5000"), item.quantity());
        assertEquals("FILLED", item.status());
        assertEquals(submitted, item.submittedAt());
        assertEquals(filled, item.filledAt());
    }

    @Test
    @DisplayName("AC4: Paginated results with configurable page size")
    void testAC4_PaginationSupported() {
        when(orderRepository.findByAccount_ClientIdOrderBySubmittedAtDescOrderIdDesc(eq(aliceClientId), eq(PageRequest.of(1, 5))))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(1, 5), 12));

        Page<OrderHistoryItem> page = orderHistoryService.getOrderHistory(aliceClientId, 1, 5);

        assertEquals(1, page.getNumber());
        assertEquals(5, page.getSize());
        assertEquals(12, page.getTotalElements());
        assertEquals(3, page.getTotalPages());
    }

    @Test
    @DisplayName("AC5: Unfilled orders return null filledAt")
    void testAC5_UnfilledOrderHasNullFilledAt() {
        Order pendingOrder = new Order(
                UUID.randomUUID(), aliceAccount, aapl, Order.Side.BUY, Order.Type.LIMIT,
                new BigDecimal("10.0000"), new BigDecimal("150.0000"), Order.Status.PENDING,
                baseTime, null);

        when(orderRepository.findByAccount_ClientIdOrderBySubmittedAtDescOrderIdDesc(eq(aliceClientId), eq(PageRequest.of(0, 20))))
                .thenReturn(new PageImpl<>(List.of(pendingOrder)));

        Page<OrderHistoryItem> page = orderHistoryService.getOrderHistory(aliceClientId, 0, 20);

        OrderHistoryItem item = page.getContent().get(0);
        assertEquals("PENDING", item.status());
        assertNull(item.filledAt());
    }
}
