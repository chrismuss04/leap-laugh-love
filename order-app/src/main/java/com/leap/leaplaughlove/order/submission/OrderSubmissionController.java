package com.leap.leaplaughlove.order.submission;

import com.leap.leaplaughlove.order.order.Order;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for order submission.
 * Endpoints are secured via Bearer JWT.
 */
@RestController
@RequestMapping("/api/order/orders")
public class OrderSubmissionController {

    private final OrderSubmissionService orderSubmissionService;

    /**
     * Constructs an instance of OrderSubmissionController with the specified OrderSubmissionService.
     * @param orderSubmissionService the service used for handling order submissions
     */
    public OrderSubmissionController(OrderSubmissionService orderSubmissionService) {
        this.orderSubmissionService = orderSubmissionService;
    }

    /**
     * Submits an order for execution under the specified account.
     * @param request the order submission request containing order details
     * @return the result of the order submission: 200 once filled or rejected, 202 while the
     *         fill is still being settled
     */
    @PostMapping
    public ResponseEntity<OrderSubmissionResponse> submitOrder(
            @Valid @RequestBody OrderSubmissionRequest request) {
        OrderSubmissionResponse response = orderSubmissionService.submitOrder(request);
        // ACCEPTED means the fill is still being settled (see PendingFillRecovery).
        if (Order.Status.ACCEPTED.name().equals(response.status())) {
            return ResponseEntity.accepted().body(response);
        }
        return ResponseEntity.ok(response);
    }
}

