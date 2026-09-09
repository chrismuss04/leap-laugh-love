package com.leap.leaplaughlove.trading.order;

import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

@RestController
public class OrderHistoryController {

    private final OrderHistoryService orderHistoryService;

    public OrderHistoryController(OrderHistoryService orderHistoryService) {
        this.orderHistoryService = orderHistoryService;
    }

    @GetMapping("/api/trading/orders/history")
    public Page<OrderHistoryItem> getOrderHistory(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        UUID authenticatedClientId = getAuthenticatedClientId();
        return orderHistoryService.getOrderHistory(authenticatedClientId, page, size);
    }

    private UUID getAuthenticatedClientId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                    "A valid authenticated principal is required");
        }
        Object principal = authentication.getPrincipal();
        if (principal instanceof UUID clientId) {
            return clientId;
        }
        if (principal instanceof String principalString && !principalString.isBlank()
                && !"anonymousUser".equals(principalString)) {
            try {
                return UUID.fromString(principalString);
            } catch (IllegalArgumentException ignored) {
                // commment fall through to unauthorized
            }
        }
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                "Authenticated principal is invalid for this operation");
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public String handleInvalidPagination(IllegalArgumentException ex) {
        return ex.getMessage();
    }
}
