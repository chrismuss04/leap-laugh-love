package com.leap.leaplaughlove.order;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests that explicitly validate the story requirements:
 * "As a client, I want my past activity to be accurately retrieved
 *  so that I can review my order history. API returns paginated chronological history."
 */
class OrderHistoryStoryTest {

    private final OrderRepository orderRepository = mock(OrderRepository.class);
    private final OrderHistoryService service = new OrderHistoryService(orderRepository);

    @Test
    void storyRequirement_AccuratelyRetrievesOrderHistory() {
        // Requirement: "accurately retrieved"
        // Verify: All order details are correctly retrieved and mapped
        
        UUID clientId = UUID.randomUUID();
        UUID orderId1 = UUID.randomUUID();
        UUID orderId2 = UUID.randomUUID();

        Order order1 = buildOrder(orderId1, "AAPL", Order.Side.BUY, 100, Order.Status.FILLED,
                OffsetDateTime.parse("2026-08-15T14:30:00Z"), 
                OffsetDateTime.parse("2026-08-15T14:35:00Z"));

        Order order2 = buildOrder(orderId2, "MSFT", Order.Side.SELL, 50, Order.Status.SUBMITTED,
                OffsetDateTime.parse("2026-08-14T10:00:00Z"), 
                null);

        Page<Order> page = new PageImpl<>(List.of(order1, order2), PageRequest.of(0, 20), 2);
        when(orderRepository.findByAccount_ClientIdOrderBySubmittedAtDescOrderIdDesc(eq(clientId), any()))
                .thenReturn(page);

        Page<OrderHistoryItem> result = service.getOrderHistory(clientId, 0, 20);

        // Verify accurate retrieval of all order details
        assertThat(result.getContent()).hasSize(2);
        
        OrderHistoryItem item1 = result.getContent().get(0);
        assertThat(item1.getOrderId()).isEqualTo(orderId1);
        assertThat(item1.getSymbol()).isEqualTo("AAPL");
        assertThat(item1.getSide()).isEqualTo("BUY");
        assertThat(item1.getQuantity()).isEqualTo(100);
        assertThat(item1.getStatus()).isEqualTo("FILLED");
        assertThat(item1.getSubmittedAt()).isEqualTo(OffsetDateTime.parse("2026-08-15T14:30:00Z"));
        assertThat(item1.getFilledAt()).isEqualTo(OffsetDateTime.parse("2026-08-15T14:35:00Z"));

        OrderHistoryItem item2 = result.getContent().get(1);
        assertThat(item2.getOrderId()).isEqualTo(orderId2);
        assertThat(item2.getSymbol()).isEqualTo("MSFT");
        assertThat(item2.getSide()).isEqualTo("SELL");
        assertThat(item2.getQuantity()).isEqualTo(50);
        assertThat(item2.getStatus()).isEqualTo("SUBMITTED");
        assertThat(item2.getSubmittedAt()).isEqualTo(OffsetDateTime.parse("2026-08-14T10:00:00Z"));
        assertThat(item2.getFilledAt()).isNull();
    }

    @Test
    void storyRequirement_ReviewOrderHistory_AllOrderDetailsIncluded() {
        // Requirement: "review my order history"
        // Verify: Response includes all relevant order details for review
        
        UUID clientId = UUID.randomUUID();
        
        Order order = buildOrder(
                UUID.randomUUID(), 
                "TSLA", 
                Order.Side.BUY, 
                25, 
                Order.Status.FILLED,
                OffsetDateTime.parse("2026-08-10T09:15:00Z"),
                OffsetDateTime.parse("2026-08-10T09:17:00Z"));

        Page<Order> page = new PageImpl<>(List.of(order), PageRequest.of(0, 20), 1);
        when(orderRepository.findByAccount_ClientIdOrderBySubmittedAtDescOrderIdDesc(eq(clientId), any()))
                .thenReturn(page);

        Page<OrderHistoryItem> result = service.getOrderHistory(clientId, 0, 20);
        OrderHistoryItem item = result.getContent().get(0);

        // Verify all fields needed to review order history are present and accessible
        assertThat(item.getOrderId()).isNotNull();
        assertThat(item.getSymbol()).isNotBlank();
        assertThat(item.getSide()).isNotBlank();
        assertThat(item.getQuantity()).isGreaterThan(0);
        assertThat(item.getStatus()).isNotBlank();
        assertThat(item.getSubmittedAt()).isNotNull();
        // filledAt can be null for unfilled orders, which is acceptable
    }

    @Test
    void storyRequirement_PaginatedChronologicalHistory_NewestFirst() {
        // Requirement: "paginated chronological history"
        // Verify: Results are in chronological order (newest first) and paginated
        
        UUID clientId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.parse("2026-09-02T15:00:00Z");

        Order newest = buildOrder(UUID.randomUUID(), "GOOGL", Order.Side.BUY, 10, Order.Status.FILLED,
                now, now.plusMinutes(5));
        Order middle = buildOrder(UUID.randomUUID(), "META", Order.Side.SELL, 20, Order.Status.SUBMITTED,
                now.minusHours(1), null);
        Order oldest = buildOrder(UUID.randomUUID(), "AMZN", Order.Side.BUY, 5, Order.Status.FILLED,
                now.minusDays(1), now.minusDays(1).plusHours(2));

        // Verify newest is first, oldest is last
        Page<Order> page = new PageImpl<>(List.of(newest, middle, oldest), PageRequest.of(0, 20), 3);
        when(orderRepository.findByAccount_ClientIdOrderBySubmittedAtDescOrderIdDesc(eq(clientId), any()))
                .thenReturn(page);

        Page<OrderHistoryItem> result = service.getOrderHistory(clientId, 0, 20);

        assertThat(result.getContent()).hasSize(3);
        assertThat(result.getContent().get(0).getSubmittedAt()).isEqualTo(now);
        assertThat(result.getContent().get(1).getSubmittedAt()).isEqualTo(now.minusHours(1));
        assertThat(result.getContent().get(2).getSubmittedAt()).isEqualTo(now.minusDays(1));
    }

    @Test
    void storyRequirement_ApiReturnsPaginatedResults() {
        // Requirement: "API returns paginated..."
        // Verify: Pagination metadata is correctly returned
        
        UUID clientId = UUID.randomUUID();
        List<Order> secondPageOrders = List.of(
                buildOrder(UUID.randomUUID(), "NFLX", Order.Side.BUY, 15, Order.Status.FILLED,
                        OffsetDateTime.parse("2026-08-20T11:00:00Z"),
                        OffsetDateTime.parse("2026-08-20T11:02:00Z")),
                buildOrder(UUID.randomUUID(), "NVDA", Order.Side.SELL, 8, Order.Status.SUBMITTED,
                        OffsetDateTime.parse("2026-08-19T09:00:00Z"), null),
                buildOrder(UUID.randomUUID(), "AMD", Order.Side.BUY, 12, Order.Status.FILLED,
                        OffsetDateTime.parse("2026-08-18T14:00:00Z"),
                        OffsetDateTime.parse("2026-08-18T14:05:00Z")),
                buildOrder(UUID.randomUUID(), "INTC", Order.Side.SELL, 20, Order.Status.FILLED,
                        OffsetDateTime.parse("2026-08-17T08:00:00Z"),
                        OffsetDateTime.parse("2026-08-17T08:10:00Z")),
                buildOrder(UUID.randomUUID(), "IBM", Order.Side.BUY, 6, Order.Status.FILLED,
                        OffsetDateTime.parse("2026-08-16T12:00:00Z"),
                        OffsetDateTime.parse("2026-08-16T12:03:00Z")));

        // Simulate page 1 (second page) of 2, with 10 items per page, 15 total
        Page<Order> page = new PageImpl<>(secondPageOrders, PageRequest.of(1, 10), 15);
        when(orderRepository.findByAccount_ClientIdOrderBySubmittedAtDescOrderIdDesc(eq(clientId), any()))
                .thenReturn(page);

        Page<OrderHistoryItem> result = service.getOrderHistory(clientId, 1, 10);

        // Verify pagination metadata
        assertThat(result.getNumber()).isEqualTo(1);           // Page number
        assertThat(result.getSize()).isEqualTo(10);            // Page size
        assertThat(result.getTotalElements()).isEqualTo(15);   // Total items
        assertThat(result.getTotalPages()).isEqualTo(2);       // Total pages
        assertThat(result.getContent()).hasSize(5);            // Items on this page
    }

    @Test
    void storyRequirement_HandlesEmptyOrderHistory() {
        // Requirement: Client should be able to review order history
        // Edge case: What if client has no orders? Should return empty gracefully, not error
        
        UUID clientId = UUID.randomUUID();
        Page<Order> emptyPage = new PageImpl<>(List.of(), PageRequest.of(0, 20), 0);
        when(orderRepository.findByAccount_ClientIdOrderBySubmittedAtDescOrderIdDesc(eq(clientId), any()))
                .thenReturn(emptyPage);

        Page<OrderHistoryItem> result = service.getOrderHistory(clientId, 0, 20);

        assertThat(result.getContent()).isEmpty();
        assertThat(result.getTotalElements()).isZero();
    }

    // Helper to build test orders
    private Order buildOrder(UUID orderId, String symbol, Order.Side side, long quantity, 
                            Order.Status status, OffsetDateTime submittedAt, OffsetDateTime filledAt) {
        Instrument instrument = new Instrument();
        ReflectionTestUtils.setField(instrument, "symbol", symbol);

        Order order = new Order();
        ReflectionTestUtils.setField(order, "orderId", orderId);
        ReflectionTestUtils.setField(order, "submittedAt", submittedAt);
        order.setInstrument(instrument);
        order.setSide(side);
        order.setQuantity(quantity);
        order.setStatus(status);
        order.setFilledAt(filledAt);
        return order;
    }
}
