package com.leap.leaplaughlove.trading.order;

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

    /**
     * Retrieves the order history for the specified client.
     * @param clientId the ID of the client whose order history is being retrieved
     * @param page the page number to retrieve
     * @param size the number of items per page
     * @return a paginated list of order history items for the specified client
     */
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
