package com.leap.leaplaughlove.order;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OrderHistoryServiceTest {

    private final OrderRepository orderRepository = mock(OrderRepository.class);
    private final OrderHistoryService service = new OrderHistoryService(orderRepository);

    @Test
    void returnsOrdersMappedToHistoryItemsInRepositoryOrder() {
        UUID clientId = UUID.randomUUID();

        Order newerOrder = buildOrder("AAPL", Order.Side.BUY, 10, Order.Status.FILLED,
                OffsetDateTime.parse("2026-08-01T10:00:00Z"), OffsetDateTime.parse("2026-08-01T10:05:00Z"));
        Order olderOrder = buildOrder("MSFT", Order.Side.SELL, 5, Order.Status.SUBMITTED,
                OffsetDateTime.parse("2026-07-01T09:00:00Z"), null);

        Page<Order> page = new PageImpl<>(List.of(newerOrder, olderOrder), PageRequest.of(0, 20), 2);
        when(orderRepository.findByAccount_ClientIdOrderBySubmittedAtDescOrderIdDesc(eq(clientId), any(Pageable.class)))
                .thenReturn(page);

        Page<OrderHistoryItem> result = service.getOrderHistory(clientId, 0, 20);

        assertThat(result.getContent()).hasSize(2);
        assertThat(result.getContent().get(0).getSymbol()).isEqualTo("AAPL");
        assertThat(result.getContent().get(0).getSide()).isEqualTo("BUY");
        assertThat(result.getContent().get(0).getStatus()).isEqualTo("FILLED");
        assertThat(result.getContent().get(1).getSymbol()).isEqualTo("MSFT");
        assertThat(result.getContent().get(1).getFilledAt()).isNull();

        // The service must preserve the repository's chronological (newest-first) order.
        assertThat(result.getContent().get(0).getSubmittedAt())
                .isAfter(result.getContent().get(1).getSubmittedAt());
    }

    @Test
    void requestsThePageAndSizeThatWereAskedFor() {
        UUID clientId = UUID.randomUUID();
        when(orderRepository.findByAccount_ClientIdOrderBySubmittedAtDescOrderIdDesc(eq(clientId), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        service.getOrderHistory(clientId, 2, 15);

        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(orderRepository).findByAccount_ClientIdOrderBySubmittedAtDescOrderIdDesc(eq(clientId), pageableCaptor.capture());

        assertThat(pageableCaptor.getValue().getPageNumber()).isEqualTo(2);
        assertThat(pageableCaptor.getValue().getPageSize()).isEqualTo(15);
    }

    @Test
    void rejectsNegativePage() {
        UUID clientId = UUID.randomUUID();

        assertThatThrownBy(() -> service.getOrderHistory(clientId, -1, 20))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsSizeBelowOne() {
        UUID clientId = UUID.randomUUID();

        assertThatThrownBy(() -> service.getOrderHistory(clientId, 0, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsSizeAboveMax() {
        UUID clientId = UUID.randomUUID();

        assertThatThrownBy(() -> service.getOrderHistory(clientId, 0, 101))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void returnsEmptyPageRatherThanErrorForClientWithNoOrders() {
        UUID clientId = UUID.randomUUID();
        when(orderRepository.findByAccount_ClientIdOrderBySubmittedAtDescOrderIdDesc(eq(clientId), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        Page<OrderHistoryItem> result = service.getOrderHistory(clientId, 0, 20);

        assertThat(result.getContent()).isEmpty();
        assertThat(result.getTotalElements()).isZero();
    }

    private Order buildOrder(String symbol, Order.Side side, long quantity, Order.Status status,
                              OffsetDateTime submittedAt, OffsetDateTime filledAt) {
        Instrument instrument = new Instrument();
        ReflectionTestUtils.setField(instrument, "symbol", symbol);

        Order order = new Order();
        ReflectionTestUtils.setField(order, "orderId", UUID.randomUUID());
        ReflectionTestUtils.setField(order, "submittedAt", submittedAt);
        order.setInstrument(instrument);
        order.setSide(side);
        order.setQuantity(quantity);
        order.setStatus(status);
        order.setFilledAt(filledAt);
        return order;
    }
}
