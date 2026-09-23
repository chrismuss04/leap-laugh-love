package com.leap.leaplaughlove.trading.order;

import com.leap.leaplaughlove.common.security.SecurityUtils;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
public class OrderHistoryController {

    private final OrderHistoryService orderHistoryService;

    public OrderHistoryController(OrderHistoryService orderHistoryService) {
        this.orderHistoryService = orderHistoryService;
    }

    /**
     * Retrieves the order history for the authenticated client.
     * @param page the page number to retrieve
     * @param size the number of items per page
     * @param year optional year filter; required when month or day is given
     * @param month optional month filter (1-12); required when day is given
     * @param day optional day-of-month filter
     * @return a paginated list of order history items for the authenticated client
     */
    @GetMapping("/api/trading/orders/history")
    public Page<OrderHistoryItem> getOrderHistory(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) Integer month,
            @RequestParam(required = false) Integer day) {
        UUID authenticatedClientId = SecurityUtils.getAuthenticatedClientId();
        return orderHistoryService.getOrderHistory(authenticatedClientId, page, size, year, month, day);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public java.util.Map<String, String> handleInvalidRequest(IllegalArgumentException ex) {
        return java.util.Map.of("message", ex.getMessage());
    }
}
