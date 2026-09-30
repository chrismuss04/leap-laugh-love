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

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
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
    // Central time (CDT/CST), not UTC, so an evening trade is filed under the date the client saw.
    private static final ZoneId FILTER_ZONE = ZoneId.of("America/Chicago");

    private record DateRange(OffsetDateTime from, OffsetDateTime to) {}

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
     * Retrieves the order history for the specified client with default null filters.
     * @param clientId the unique identifier of the client
     * @param page the page number to retrieve
     * @param size the number of items per page
     * @return a paginated list of order history items for the specified client
     */
    @Transactional(readOnly = true)
    public Page<OrderHistoryItem> getOrderHistory(UUID clientId, int page, int size) {
        return getOrderHistory(clientId, page, size, null, null, null);
    }

    /**
     * Retrieves the order history for the specified client.
     * @param clientId the unique identifier of the client
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
                ? orderRepository.findByClientId(clientId, pageable)
                : orderRepository.findByClientIdSubmittedBetween(clientId, range.from(), range.to(), pageable);

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
     * Converts the provided year, month, and day into a DateRange object.
     * @param year the year component of the date range
     * @param month the month component of the date range
     * @param day the day component of the date range
     * @return a DateRange object representing the specified date range, or null if no date range is specified
     * @throws IllegalArgumentException if the provided date components are invalid or inconsistent
     */
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

