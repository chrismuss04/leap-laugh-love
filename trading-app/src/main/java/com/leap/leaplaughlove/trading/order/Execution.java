package com.leap.leaplaughlove.trading.order;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

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

    protected Execution() {
    }

    public Execution(UUID executionId, Order order, BigDecimal quantity, BigDecimal price, OffsetDateTime executedAt) {
        this.executionId = executionId;
        this.order = order;
        this.quantity = quantity;
        this.price = price;
        this.executedAt = executedAt;
    }

    public UUID getExecutionId() { return executionId; }
    public Order getOrder() { return order; }
    public BigDecimal getQuantity() { return quantity; }
    public BigDecimal getPrice() { return price; }
    public OffsetDateTime getExecutedAt() { return executedAt; }
}
