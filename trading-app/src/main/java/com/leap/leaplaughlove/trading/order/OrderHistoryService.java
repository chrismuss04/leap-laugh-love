package com.leap.leaplaughlove.trading.order;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class OrderHistoryService {

    private static final int MAX_PAGE_SIZE = 100;
    // Central time (CDT/CST), not UTC, so an evening trade is filed under the date the client saw.
    private static final ZoneId FILTER_ZONE = ZoneId.of("America/Chicago");

    private record DateRange(OffsetDateTime from, OffsetDateTime to) {}

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
     * @param year optional year filter; required when month or day is given
     * @param month optional month filter (1-12); required when day is given
     * @param day optional day-of-month filter
     * @return a paginated list of order history items for the specified client
     */
    @Transactional(readOnly = true)
    public Page<OrderHistoryItem> getOrderHistory(UUID clientId, int page, int size,
                                                  Integer year, Integer month, Integer day) {
        if (page < 0) {
            throw new IllegalArgumentException("page must not be negative");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("size must be between 1 and " + MAX_PAGE_SIZE);
        }
        DateRange range = toDateRange(year, month, day);
        Pageable pageable = PageRequest.of(page, size);
        Page<Order> orders = range == null
                ? orderRepository.findByAccount_ClientIdOrderBySubmittedAtDescOrderIdDesc(clientId, pageable)
                : orderRepository
                        .findByAccount_ClientIdAndSubmittedAtGreaterThanEqualAndSubmittedAtLessThanOrderBySubmittedAtDescOrderIdDesc(
                                clientId, range.from(), range.to(), pageable);

        List<UUID> orderIds = orders.map(Order::getOrderId).getContent();
        // Grouped, not collected into one-per-order: an order can fill in several executions,
        // and keeping only the first reported a partial fill as if it were the whole order.
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

    private DateRange toDateRange(Integer year, Integer month, Integer day) {
        if (year == null) {
            if (month != null || day != null) {
                throw new IllegalArgumentException("year is required when month or day is provided");
            }
            return null;
        }
        if (month == null && day != null) {
            throw new IllegalArgumentException("month is required when day is provided");
        }
        LocalDate start;
        LocalDate end;
        try {
            if (month == null) {
                start = LocalDate.of(year, 1, 1);
                end = start.plusYears(1);
            } else if (day == null) {
                start = LocalDate.of(year, month, 1);
                end = start.plusMonths(1);
            } else {
                start = LocalDate.of(year, month, day);
                end = start.plusDays(1);
            }
        } catch (DateTimeException ex) {
            throw new IllegalArgumentException("invalid date filter: " + ex.getMessage(), ex);
        }
        return new DateRange(
                start.atStartOfDay(FILTER_ZONE).toOffsetDateTime(),
                end.atStartOfDay(FILTER_ZONE).toOffsetDateTime());
    }

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

    private ExecutionItem toExecutionItem(Execution execution) {
        return new ExecutionItem(
                execution.getExecutionId(),
                execution.getFillQuantity(),
                execution.getFillPrice(),
                execution.getExecutedAt());
    }
}
