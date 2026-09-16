package com.leap.leaplaughlove.trading.order;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class OrderHistoryService {

    private static final int MAX_PAGE_SIZE = 100;

    private final OrderRepository orderRepository;
    private final ExecutionRepository executionRepository;

    public OrderHistoryService(OrderRepository orderRepository, ExecutionRepository executionRepository) {
        this.orderRepository = orderRepository;
        this.executionRepository = executionRepository;
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
        Page<Order> orders = orderRepository
                .findByAccount_ClientIdOrderBySubmittedAtDescOrderIdDesc(clientId, pageable);

        List<UUID> orderIds = orders.map(Order::getOrderId).getContent();
        Map<UUID, ExecutionItem> executionByOrderId = executionRepository
                .findByOrder_OrderIdIn(orderIds).stream()
                .filter(execution -> execution.getStatus() == Execution.Status.FILLED)
                .collect(Collectors.toMap(
                        execution -> execution.getOrder().getOrderId(),
                        this::toExecutionItem,
                        (existing, replacement) -> existing));

        return orders.map(order -> toHistoryItem(order, executionByOrderId));
    }

    private OrderHistoryItem toHistoryItem(Order order, Map<UUID, ExecutionItem> executionByOrderId) {
        return new OrderHistoryItem(
                order.getOrderId(),
                order.getInstrument().getSymbol(),
                order.getSide().name(),
                order.getQuantity(),
                order.getStatus().name(),
                order.getSubmittedAt(),
                order.getFilledAt(),
                executionByOrderId.get(order.getOrderId()));
    }

    private ExecutionItem toExecutionItem(Execution execution) {
        return new ExecutionItem(
                execution.getExecutionId(),
                execution.getFillQuantity(),
                execution.getFillPrice(),
                execution.getExecutedAt());
    }
}
