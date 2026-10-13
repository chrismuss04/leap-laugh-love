package com.leap.leaplaughlove.order.working;

import com.leap.leaplaughlove.common.security.SecurityUtils;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
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
    private final OrderModificationService orderModificationService;

    /**
     * Constructs an OpenOrderController.
     * @param openOrderService lists and cancels open orders
     * @param orderModificationService edits open orders
     */
    public OpenOrderController(OpenOrderService openOrderService, OrderModificationService orderModificationService) {
        this.openOrderService = openOrderService;
        this.orderModificationService = orderModificationService;
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
     * Changes one of the client's open orders in place: its quantity, price, trail or time in force.
     * @param orderId the order to change
     * @param request what to change; only the fields given
     * @return the order as it now stands; 404 if the client has no such order, 409 if it is no
     *         longer open, 400 if the edit doesn't fit the order or the account can't cover it,
     *         404 or 403 if account-app refuses the account, 503 if account-app is unavailable
     */
    @PatchMapping("/{orderId}")
    public OpenOrderItem modifyOrder(@PathVariable UUID orderId, @Valid @RequestBody ModifyOrderRequest request) {
        return orderModificationService.modify(SecurityUtils.getAuthenticatedClientId(), orderId, request);
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
