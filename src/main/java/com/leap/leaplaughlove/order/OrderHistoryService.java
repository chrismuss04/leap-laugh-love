package com.leap.leaplaughlove.order;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class OrderHistoryService {

    private static final int MAX_PAGE_SIZE = 100;

    private final OrderRepository orderRepository;

    public OrderHistoryService(OrderRepository orderRepository) {
        this.orderRepository = orderRepository;
    }

    public Page<OrderHistoryItem> getOrderHistory(UUID clientId, int page, int size) {
        if (page < 0) {
            throw new IllegalArgumentException("page must not be negative");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("size must be between 1 and " + MAX_PAGE_SIZE);
        }
        Pageable pageable = PageRequest.of(page, size);
        return orderRepository
                .findByAccount_ClientIdOrderBySubmittedAtDescOrderIdDesc(clientId, pageable)
                .map(this::toHistoryItem);
    }

    private OrderHistoryItem toHistoryItem(Order order) {
        return new OrderHistoryItem(
                order.getOrderId(),
                order.getInstrument().getSymbol(),
                order.getSide().name(),
                order.getQuantity(),
                order.getStatus().name(),
                order.getSubmittedAt(),
                order.getFilledAt());
    }
}
