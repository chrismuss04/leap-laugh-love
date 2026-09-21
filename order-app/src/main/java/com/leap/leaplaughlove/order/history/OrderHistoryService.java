package com.leap.leaplaughlove.order.history;

import com.leap.leaplaughlove.order.client.AccountClient;
import com.leap.leaplaughlove.order.execution.Execution;
import com.leap.leaplaughlove.order.execution.ExecutionItem;
import com.leap.leaplaughlove.order.execution.ExecutionRepository;
import com.leap.leaplaughlove.order.order.Order;
import com.leap.leaplaughlove.order.order.OrderRepository;
import com.leap.leaplaughlove.order.instrument.Instrument;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Service for retrieving order history for clients.
 * Provides methods to fetch paginated order history along with associated executions.
 * @see OrderHistoryController
 */
@Service
public class OrderHistoryService {

    private static final int MAX_PAGE_SIZE = 100;

    private final OrderRepository orderRepository;
    private final ExecutionRepository executionRepository;
    private final AccountClient accountClient;

    /**
     * Constructs an instance of OrderHistoryService with the specified repositories and client.
     * @param orderRepository the repository used to access order data
     * @param executionRepository the repository used to access execution data
     * @param accountClient the client used to access account information
     */
    public OrderHistoryService(OrderRepository orderRepository,
                               ExecutionRepository executionRepository,
                               AccountClient accountClient) {
        this.orderRepository = orderRepository;
        this.executionRepository = executionRepository;
        this.accountClient = accountClient;
    }

    /**
     * Retrieves the order history for the specified client.
     * @param clientId the unique identifier of the client
     * @param page the page number to retrieve
     * @param size the number of items per page
     * @return a paginated list of order history items
     * @throws IllegalArgumentException if the page or size parameters are invalid
     */
    @Transactional(readOnly = true)
    public Page<OrderHistoryItem> getOrderHistory(UUID clientId, int page, int size) {
        if (page < 0) {
            throw new IllegalArgumentException("page must not be negative");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("size must be between 1 and " + MAX_PAGE_SIZE);
        }
        Pageable pageable = PageRequest.of(page, size);

        List<UUID> accountIds = accountClient.getAccountIdsForClient();
        if (accountIds == null || accountIds.isEmpty()) {
            return new PageImpl<>(List.of(), pageable, 0);
        }

        Page<Order> orders = orderRepository
                .findByAccountIdInOrderBySubmittedAtDescOrderIdDesc(accountIds, pageable);

        List<UUID> orderIds = orders.map(Order::getOrderId).getContent();
        Map<UUID, List<ExecutionItem>> executionsByOrderId = executionRepository
                .findByOrder_OrderIdIn(orderIds).stream()
                .filter(execution -> execution.getStatus() == Execution.Status.FILLED)
                .sorted(Comparator.comparing(Execution::getExecutedAt,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .collect(Collectors.groupingBy(
                        execution -> execution.getOrder().getOrderId(),
                        Collectors.mapping(this::toExecutionItem, Collectors.toList())));

        return orders.map(order -> toHistoryItem(order, executionsByOrderId));
    }

    /**
     * Converts an Order entity and its associated executions into an OrderHistoryItem DTO
     * @param order the order entity to be converted
     * @param executionsByOrderId a map of order IDs to their associated execution items
     * @return the corresponding OrderHistoryItem DTO
     */
    private OrderHistoryItem toHistoryItem(Order order, Map<UUID, List<ExecutionItem>> executionsByOrderId) {
        return new OrderHistoryItem(
                order.getOrderId(),
                order.getInstrument().getSymbol(),
                order.getSide().name(),
                order.getQuantity(),
                order.getStatus().name(),
                order.getSubmittedAt(),
                order.getFilledAt(),
                executionsByOrderId.getOrDefault(order.getOrderId(), List.of()));
    }

    /**
     * Converts an Execution entity into a ExecutionItem DTO
     * @param execution the execution entity to be converted
     * @return the corresponding ExecutionItem DTO
     */
    private ExecutionItem toExecutionItem(Execution execution) {
        return new ExecutionItem(
                execution.getExecutionId(),
                execution.getFillQuantity(),
                execution.getFillPrice(),
                execution.getExecutedAt());
    }
}

