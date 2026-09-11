package com.leap.leaplaughlove.trading.order;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;
/**
 * Represents the execution of an order in the trading system
 */
@Entity
@Table(name = "executions", schema = "trading")
public class Execution {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "execution_id")
    private UUID executionId;

    @ManyToOne
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;

    @Column(name = "quantity", nullable = false, precision = 15, scale = 4)
    private BigDecimal quantity;

    @Column(name = "price", nullable = false, precision = 15, scale = 4)
    private BigDecimal price;

    @Column(name = "executed_at", nullable = false)
    private OffsetDateTime executedAt;

    /**
     * Protected no-argument constructor for JPA.
     */
    protected Execution() {
    }

    /**
     * Method to create a new Execution entity with the specified details.
     * @param executionId the unique identifier of the execution
     * @param order the order associated with this execution
     * @param quantity the quantity executed
     * @param price the price at which the execution occurred
     * @param executedAt the timestamp when the execution took place
     */
    public Execution(UUID executionId, Order order, BigDecimal quantity, BigDecimal price, OffsetDateTime executedAt) {
        this.executionId = executionId;
        this.order = order;
        this.quantity = quantity;
        this.price = price;
        this.executedAt = executedAt;
    }
    /**
     * Gets the unique identifier of the execution.
     * @return the execution ID
     */
    public UUID getExecutionId() { return executionId; }
    /**
     * Gets the order associated with this execution.
     * @return the order
     */
    public Order getOrder() { return order; }

    /**
     * Gets the quantity executed.
     * @return the quantity
     */
    public BigDecimal getQuantity() { return quantity; }

    /**
     * Gets the price at which the execution occurred.
     * @return the price
     */
    public BigDecimal getPrice() { return price; }

    /**
     * Gets the timestamp when the execution took place.
     * @return the execution timestamp
     */
    public OffsetDateTime getExecutedAt() { return executedAt; }
}
