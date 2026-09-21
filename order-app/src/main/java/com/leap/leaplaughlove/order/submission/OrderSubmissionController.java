package com.leap.leaplaughlove.order.submission;

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
     * @return the response containing the result of the order submission
     */
    @PostMapping
    public ResponseEntity<OrderSubmissionResponse> submitOrder(
            @Valid @RequestBody OrderSubmissionRequest request) {
        OrderSubmissionResponse response = orderSubmissionService.submitOrder(request);
        return ResponseEntity.ok(response);
    }
}

