package com.leap.leaplaughlove.trading.position;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Represents an entry in the position movements ledger (trading.position_movements).
 * An immutable audit record capturing changes to account holdings.
 */
@Entity
@Table(name = "position_movements", schema = "trading")
public class PositionMovement {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "movement_id")
    private UUID movementId;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Column(name = "instrument_id", nullable = false)
    private UUID instrumentId;

    @Column(name = "order_id")
    private UUID orderId;

    @Column(name = "execution_id")
    private UUID executionId;

    @Column(name = "movement_type", nullable = false)
    private String movementType;

    @Column(name = "quantity_delta", nullable = false)
    private long quantityDelta;

    @Column(name = "cost_delta", nullable = false, precision = 18, scale = 2)
    private BigDecimal costDelta;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    protected PositionMovement() {
    }

    public PositionMovement(UUID accountId, UUID instrumentId, UUID orderId, UUID executionId,
                            String movementType, long quantityDelta, BigDecimal costDelta, OffsetDateTime createdAt) {
        this.accountId = accountId;
        this.instrumentId = instrumentId;
        this.orderId = orderId;
        this.executionId = executionId;
        this.movementType = movementType;
        this.quantityDelta = quantityDelta;
        this.costDelta = costDelta != null ? costDelta : BigDecimal.ZERO;
        this.createdAt = createdAt != null ? createdAt : OffsetDateTime.now();
    }

    public UUID getMovementId() { return movementId; }
    public UUID getAccountId() { return accountId; }
    public UUID getInstrumentId() { return instrumentId; }
    public UUID getOrderId() { return orderId; }
    public UUID getExecutionId() { return executionId; }
    public String getMovementType() { return movementType; }
    public long getQuantityDelta() { return quantityDelta; }
    public BigDecimal getCostDelta() { return costDelta; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
}

