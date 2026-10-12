package com.leap.leaplaughlove.order.working;

import com.leap.leaplaughlove.common.security.SecurityUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * REST controller for the authenticated client's open orders: limit and stop orders waiting
 * for the market to reach their price. Endpoints are secured via Bearer JWT.
 */
@RestController
@RequestMapping("/api/order/orders")
public class OpenOrderController {

    private final OpenOrderService openOrderService;

    /**
     * Constructs an OpenOrderController.
     * @param openOrderService lists and cancels open orders
     */
    public OpenOrderController(OpenOrderService openOrderService) {
        this.openOrderService = openOrderService;
    }

    /**
     * Lists the client's open orders across all their accounts, newest first.
     * @return the open orders
     */
    @GetMapping("/open")
    public List<OpenOrderItem> getOpenOrders() {
        return openOrderService.listOpen(SecurityUtils.getAuthenticatedClientId());
    }

    /**
     * Cancels one of the client's open orders.
     * @param orderId the order to cancel
     * @return the cancelled order; 404 if the client has no such order, 409 if it is no longer open
     */
    @PostMapping("/{orderId}/cancel")
    public OpenOrderItem cancelOrder(@PathVariable UUID orderId) {
        return openOrderService.cancel(SecurityUtils.getAuthenticatedClientId(), orderId);
    }
}
