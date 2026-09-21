package com.leap.leaplaughlove.order.execution;

import com.leap.leaplaughlove.order.order.Order;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Represents the execution record of an order in the trading system.
 * Immutable audit record that captures fill details or rejection reasons.
 */
@Entity
@Table(name = "executions", schema = "trading")
public class Execution {

    public enum Status { FILLED, REJECTED }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "execution_id")
    private UUID executionId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;

    @Column(name = "fill_quantity")
    private Long fillQuantity;

    @Column(name = "fill_price", precision = 18, scale = 6)
    private BigDecimal fillPrice;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private Status status;

    @Column(name = "executed_at", nullable = false)
    private OffsetDateTime executedAt;

    @Column(name = "reason")
    private String reason;

    /**
     * Protected no-argument constructor for JPA.
     */
    protected Execution() {
    }
    /**
     * Constructs a new Execution with the specified details.
     * @param executionId the unique identifier of the execution
     * @param order the order associated with this execution
     * @param fillQuantity the quantity filled in this execution
     * @param fillPrice the price at which the order was filled
     * @param status the status of the execution (FILLED or REJECTED)
     * @param reason the reason for rejection, if applicable
     * @param executedAt the timestamp when the execution occurred
     */
    public Execution(UUID executionId, Order order, Long fillQuantity, BigDecimal fillPrice,
                     Status status, String reason, OffsetDateTime executedAt) {
        this.executionId = executionId;
        this.order = order;
        this.fillQuantity = fillQuantity;
        this.fillPrice = fillPrice;
        this.status = status;
        this.reason = reason;
        this.executedAt = executedAt != null ? executedAt : OffsetDateTime.now();
    }

    /**
     * Constructs a new Execution without specifying an execution ID. The execution ID will be generated automatically.
     * @param order the order associated with this execution
     * @param fillQuantity the quantity filled in this execution
     * @param fillPrice the price at which the order was filled
     * @param status the status of the execution (FILLED or REJECTED)
     * @param reason the reason for rejection, if applicable
     * @param executedAt the timestamp when the execution occurred
     */
    public Execution(Order order, Long fillQuantity, BigDecimal fillPrice,
                     Status status, String reason, OffsetDateTime executedAt) {
        this(null, order, fillQuantity, fillPrice, status, reason, executedAt);
    }
    
    /**
     * Returns the unique identifier of the execution.
     * @return the execution ID
     */
    public UUID getExecutionId() { return executionId; }
    /**
     * Returns the Order object associated with this execution.
     * @return the order associated with this execution
     */
    public Order getOrder() { return order; }
    /**
     * Returns the quantity filled in this execution.
     * @return the fill quantity
     */
    public Long getFillQuantity() { return fillQuantity; }

    /**
     * Returns the price at which the order was filled.
     * @return the fill price
     */
    public BigDecimal getFillPrice() { return fillPrice; }

    /**
     * Returns the status of the execution (FILLED or REJECTED).
     * @return the execution status
     */
    public Status getStatus() { return status; }

    /**
     * Returns the timestamp when the execution occurred.
     * @return the execution timestamp
     */
    public OffsetDateTime getExecutedAt() { return executedAt; }

    /**
     * Returns the reason for rejection, if applicable.
     * @return the rejection reason
     */
    public String getReason() { return reason; }
}

