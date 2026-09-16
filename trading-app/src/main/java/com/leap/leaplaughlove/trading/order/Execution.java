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

    @Column(name = "fill_quantity")
    private Long quantity;

    @Column(name = "fill_price", precision = 18, scale = 6)
    private BigDecimal price;

    @Column(name = "executed_at", nullable = false)
    private OffsetDateTime executedAt;

    /**
     * Protected no-argument constructor for JPA.
     */
    protected Execution() {
    }

    public Execution(UUID executionId, Order order, Long quantity, BigDecimal price, OffsetDateTime executedAt) {
        this.executionId = executionId;
        this.order = order;
        this.quantity = quantity;
        this.price = price;
        this.executedAt = executedAt;
    }

    public UUID getExecutionId() { return executionId; }
    public Order getOrder() { return order; }
    public Long getQuantity() { return quantity; }
    public BigDecimal getPrice() { return price; }
    public OffsetDateTime getExecutedAt() { return executedAt; }
}
