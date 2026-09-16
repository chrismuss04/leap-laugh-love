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
     * @return a paginated list of order history items for the authenticated client
     */
    @GetMapping("/api/trading/orders/history")
    public Page<OrderHistoryItem> getOrderHistory(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        UUID authenticatedClientId = SecurityUtils.getAuthenticatedClientId();
        return orderHistoryService.getOrderHistory(authenticatedClientId, page, size);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public java.util.Map<String, String> handleInvalidPagination(IllegalArgumentException ex) {
        return java.util.Map.of("message", ex.getMessage());
    }
}
