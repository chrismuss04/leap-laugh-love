package com.leap.leaplaughlove.trading.order;

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

    /**
     * The status of the execution matching the DB constraint: FILLED or REJECTED.
     */
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
     * Constructor for creating a new Execution entity with full audit details.
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
     * Convenience constructor without explicit ID (DB/JPA generated).
     */
    public Execution(Order order, Long fillQuantity, BigDecimal fillPrice,
                     Status status, String reason, OffsetDateTime executedAt) {
        this(null, order, fillQuantity, fillPrice, status, reason, executedAt);
    }

    public UUID getExecutionId() { return executionId; }
    public Order getOrder() { return order; }
    public Long getFillQuantity() { return fillQuantity; }
    public BigDecimal getFillPrice() { return fillPrice; }
    public Status getStatus() { return status; }
    public OffsetDateTime getExecutedAt() { return executedAt; }
    public String getReason() { return reason; }
}
