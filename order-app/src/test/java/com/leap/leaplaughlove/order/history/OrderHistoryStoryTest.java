package com.leap.leaplaughlove.order.history;

import com.leap.leaplaughlove.order.client.AccountClient;
import com.leap.leaplaughlove.order.execution.ExecutionRepository;
import com.leap.leaplaughlove.order.instrument.Instrument;
import com.leap.leaplaughlove.order.order.Order;
import com.leap.leaplaughlove.order.order.OrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("Story PB-07 Acceptance Criteria Tests")
class OrderHistoryStoryTest {

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private ExecutionRepository executionRepository;

    @Mock
    private AccountClient accountClient;

    private OrderHistoryService orderHistoryService;

    private UUID aliceClientId;
    private UUID aliceAccountId;
    private Instrument aapl;
    private Instrument msft;
    private OffsetDateTime baseTime;

    @BeforeEach
    void setUp() {
        orderHistoryService = new OrderHistoryService(orderRepository, executionRepository, accountClient);
        lenient().when(executionRepository.findByOrder_OrderIdIn(any())).thenReturn(List.of());

        aliceClientId = UUID.randomUUID();
        aliceAccountId = UUID.randomUUID();
        lenient().when(accountClient.getAccountIdsForClient()).thenReturn(List.of(aliceAccountId));

        aapl = new Instrument(UUID.randomUUID(), "AAPL", "Apple Inc.", "EQUITY", "NASDAQ", "USD", true);
        msft = new Instrument(UUID.randomUUID(), "MSFT", "Microsoft Corp.", "EQUITY", "NASDAQ", "USD", true);

        baseTime = OffsetDateTime.now();
    }

    @Test
    @DisplayName("AC1: Returns only orders belonging to the requested client")
    void testAC1_OnlyRequestedClientOrdersReturned() {
        Order aliceOrder = new Order(
                UUID.randomUUID(), aliceAccountId, aapl, Order.Side.BUY,
                100L, Order.Status.FILLED, baseTime, baseTime.plusSeconds(1), null, baseTime.plusSeconds(2), null);

        when(orderRepository.findByClientId(eq(aliceClientId), eq(PageRequest.of(0, 20))))
                .thenReturn(new PageImpl<>(List.of(aliceOrder)));

        Page<OrderHistoryItem> page = orderHistoryService.getOrderHistory(aliceClientId, 0, 20, null, null, null);

        assertEquals(1, page.getTotalElements());
        verify(orderRepository).findByClientId(eq(aliceClientId), eq(PageRequest.of(0, 20)));
    }

    @Test
    @DisplayName("AC2: Orders ordered newest to oldest by submittedAt")
    void testAC2_OrderedNewestToOldest() {
        Order newerOrder = new Order(
                UUID.randomUUID(), aliceAccountId, msft, Order.Side.SELL,
                50L, Order.Status.FILLED,
                baseTime.plusMinutes(10), baseTime.plusMinutes(10).plusSeconds(1), null, baseTime.plusMinutes(11), null);

        Order olderOrder = new Order(
                UUID.randomUUID(), aliceAccountId, aapl, Order.Side.BUY,
                100L, Order.Status.FILLED,
                baseTime, baseTime.plusSeconds(1), null, baseTime.plusSeconds(2), null);

        when(orderRepository.findByClientId(eq(aliceClientId), eq(PageRequest.of(0, 20))))
                .thenReturn(new PageImpl<>(List.of(newerOrder, olderOrder)));

        Page<OrderHistoryItem> page = orderHistoryService.getOrderHistory(aliceClientId, 0, 20, null, null, null);

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
                orderId, aliceAccountId, aapl, Order.Side.BUY,
                25L, Order.Status.FILLED, submitted, submitted.plusSeconds(1), null, filled, null);

        when(orderRepository.findByClientId(eq(aliceClientId), eq(PageRequest.of(0, 20))))
                .thenReturn(new PageImpl<>(List.of(order)));

        Page<OrderHistoryItem> page = orderHistoryService.getOrderHistory(aliceClientId, 0, 20, null, null, null);

        OrderHistoryItem item = page.getContent().get(0);
        assertEquals(orderId, item.orderId());
        assertEquals("AAPL", item.symbol());
        assertEquals("BUY", item.side());
        assertEquals(25L, item.quantity());
        assertEquals("FILLED", item.status());
        assertEquals(submitted, item.submittedAt());
        assertEquals(filled, item.filledAt());
    }

    @Test
    @DisplayName("AC4: Paginated results with configurable page size")
    void testAC4_PaginationSupported() {
        when(orderRepository.findByClientId(eq(aliceClientId), eq(PageRequest.of(1, 5))))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(1, 5), 12));

        Page<OrderHistoryItem> page = orderHistoryService.getOrderHistory(aliceClientId, 1, 5, null, null, null);

        assertEquals(1, page.getNumber());
        assertEquals(5, page.getSize());
        assertEquals(12, page.getTotalElements());
        assertEquals(3, page.getTotalPages());
    }

    @Test
    @DisplayName("AC5: Unfilled orders return null filledAt")
    void testAC5_UnfilledOrderHasNullFilledAt() {
        Order unfilledOrder = new Order(
                UUID.randomUUID(), aliceAccountId, aapl, Order.Side.BUY,
                10L, Order.Status.SUBMITTED,
                baseTime, null, null, null, null);

        when(orderRepository.findByClientId(eq(aliceClientId), eq(PageRequest.of(0, 20))))
                .thenReturn(new PageImpl<>(List.of(unfilledOrder)));

        Page<OrderHistoryItem> page = orderHistoryService.getOrderHistory(aliceClientId, 0, 20, null, null, null);

        OrderHistoryItem item = page.getContent().get(0);
        assertEquals("SUBMITTED", item.status());
        assertNull(item.filledAt());
    }

    private static final ZoneOffset CST = ZoneOffset.ofHours(-6);
    private static final ZoneOffset CDT = ZoneOffset.ofHours(-5);

    private Page<Order> filteredHistory(UUID clientId, OffsetDateTime from, OffsetDateTime to) {
        return orderRepository
                .findByClientIdSubmittedBetween(
                        eq(clientId), eq(from), eq(to), eq(PageRequest.of(0, 20)));
    }

    private Order aliceOrder(Instrument instrument, OffsetDateTime submittedAt) {
        return new Order(
                UUID.randomUUID(), aliceAccountId, instrument, Order.Side.BUY,
                10L, Order.Status.SUBMITTED, submittedAt, null, null, null, null);
    }

    @Test
    @DisplayName("Filtering by year returns only that year's orders (Central time)")
    void testAC6_FilterByYear() {
        OffsetDateTime from = OffsetDateTime.of(2025, 1, 1, 0, 0, 0, 0, CST);
        OffsetDateTime to = OffsetDateTime.of(2026, 1, 1, 0, 0, 0, 0, CST);
        Order march2025 = aliceOrder(msft, OffsetDateTime.of(2025, 3, 12, 10, 15, 0, 0, ZoneOffset.UTC));

        when(filteredHistory(aliceClientId, from, to))
                .thenReturn(new PageImpl<>(List.of(march2025)));

        Page<OrderHistoryItem> page = orderHistoryService.getOrderHistory(aliceClientId, 0, 20, 2025, null, null);

        assertEquals(1, page.getTotalElements());
        assertEquals("MSFT", page.getContent().get(0).symbol());
    }

    @Test
    @DisplayName("Filtering by year and month returns only that month's orders (Central time)")
    void testAC7_FilterByMonth() {
        OffsetDateTime from = OffsetDateTime.of(2026, 8, 1, 0, 0, 0, 0, CDT);
        OffsetDateTime to = OffsetDateTime.of(2026, 9, 1, 0, 0, 0, 0, CDT);
        Order august2026 = aliceOrder(aapl, OffsetDateTime.of(2026, 8, 19, 10, 10, 0, 0, ZoneOffset.UTC));

        when(filteredHistory(aliceClientId, from, to))
                .thenReturn(new PageImpl<>(List.of(august2026)));

        Page<OrderHistoryItem> page = orderHistoryService.getOrderHistory(aliceClientId, 0, 20, 2026, 8, null);

        assertEquals(1, page.getTotalElements());
        assertEquals("AAPL", page.getContent().get(0).symbol());
    }

    @Test
    @DisplayName("Filtering by year, month and day returns only that day's orders (Central time)")
    void testAC8_FilterByDay() {
        OffsetDateTime from = OffsetDateTime.of(2026, 8, 3, 0, 0, 0, 0, CDT);
        OffsetDateTime to = OffsetDateTime.of(2026, 8, 4, 0, 0, 0, 0, CDT);
        Order afternoon = aliceOrder(aapl, OffsetDateTime.of(2026, 8, 3, 15, 55, 0, 0, ZoneOffset.UTC));
        Order morning = aliceOrder(aapl, OffsetDateTime.of(2026, 8, 3, 13, 20, 0, 0, ZoneOffset.UTC));

        when(filteredHistory(aliceClientId, from, to))
                .thenReturn(new PageImpl<>(List.of(afternoon, morning)));

        Page<OrderHistoryItem> page = orderHistoryService.getOrderHistory(aliceClientId, 0, 20, 2026, 8, 3);

        assertEquals(2, page.getTotalElements());
        assertTrue(page.getContent().get(0).submittedAt().isAfter(page.getContent().get(1).submittedAt()));
    }

    @Test
    @DisplayName("A filter that matches no orders returns an empty page, not an error")
    void testAC9_FilterWithNoMatchesReturnsEmptyPage() {
        OffsetDateTime from = OffsetDateTime.of(2026, 8, 4, 0, 0, 0, 0, CDT);
        OffsetDateTime to = OffsetDateTime.of(2026, 8, 5, 0, 0, 0, 0, CDT);

        when(filteredHistory(aliceClientId, from, to))
                .thenReturn(new PageImpl<>(List.of()));

        Page<OrderHistoryItem> page = orderHistoryService.getOrderHistory(aliceClientId, 0, 20, 2026, 8, 4);

        assertEquals(0, page.getTotalElements());
        assertTrue(page.getContent().isEmpty());
    }

    @Test
    @DisplayName("A filtered query is always scoped to the requesting client")
    void testAC10_FilterNeverQueriesAnotherClient() {
        UUID otherClientId = UUID.randomUUID();
        OffsetDateTime from = OffsetDateTime.of(2025, 1, 1, 0, 0, 0, 0, CST);
        OffsetDateTime to = OffsetDateTime.of(2026, 1, 1, 0, 0, 0, 0, CST);

        when(filteredHistory(aliceClientId, from, to))
                .thenReturn(new PageImpl<>(List.of()));

        orderHistoryService.getOrderHistory(aliceClientId, 0, 20, 2025, null, null);

        verify(orderRepository).findByClientIdSubmittedBetween(
                eq(aliceClientId), any(), any(), any());
        verify(orderRepository, never()).findByClientIdSubmittedBetween(
                eq(otherClientId), any(), any(), any());
    }
}

