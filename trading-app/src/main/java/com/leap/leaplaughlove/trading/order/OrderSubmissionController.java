package com.leap.leaplaughlove.trading.order;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * REST controller for order submission in the trading system.
 * Endpoints are secured via Bearer JWT and verify client account authorization.
 */
@RestController
@RequestMapping("/api/trading/orders")
public class OrderSubmissionController {

    private final OrderSubmissionService orderSubmissionService;

    public OrderSubmissionController(OrderSubmissionService orderSubmissionService) {
        this.orderSubmissionService = orderSubmissionService;
    }

    /**
     * Submits an order for execution under the specified account.
     *
     * @param request the order submission request details
     * @return the order submission response containing order, execution, and balance states
     */
    @PostMapping
    public ResponseEntity<OrderSubmissionResponse> submitOrder(
            @Valid @RequestBody OrderSubmissionRequest request) {
        OrderSubmissionResponse response = orderSubmissionService.submitOrder(request);
        return ResponseEntity.ok(response);
    }

    /**
     * Convenience endpoint for submitting an order scoped by account ID in path.
     *
     * @param accountId the account ID
     * @param request the order submission request details
     * @return the order submission response
     */
    @PostMapping("/accounts/{accountId}")
    public ResponseEntity<OrderSubmissionResponse> submitOrderForAccount(
            @PathVariable UUID accountId,
            @Valid @RequestBody OrderSubmissionRequest request) {
        OrderSubmissionRequest scopedRequest = new OrderSubmissionRequest(
                accountId,
                request.symbol(),
                request.instrumentId(),
                request.side(),
                request.quantity(),
                request.price()
        );
        OrderSubmissionResponse response = orderSubmissionService.submitOrder(scopedRequest);
        return ResponseEntity.ok(response);
    }
}

