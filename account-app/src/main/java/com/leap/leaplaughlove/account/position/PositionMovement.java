package com.leap.leaplaughlove.account.position;

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

    /**
     * Protected no-argument constructor for JPA.
     */
    protected PositionMovement() {
    }

    /**
     * Constructs a new PositionMovement instance with the specified details.
     * @param accountId the ID of the account associated with this position movement
     * @param instrumentId the ID of the instrument associated with this position movement
     * @param orderId the ID of the order that triggered this position movement, if applicable
     * @param executionId the ID of the execution that triggered this position movement, if applicable
     * @param movementType the type of movement (e.g., "BUY", "SELL")
     * @param quantityDelta the change in quantity for this position movement
     * @param costDelta the change in cost for this position movement
     * @param createdAt the timestamp when this position movement was created
     */
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

    /**
     * Returns the id of the position movement.
     * @return the UUID of the position movement
     */
    public UUID getMovementId() { return movementId; }
    /**
     * Returns the account id associated with this position movement.
     * @return the UUID of the account associated with this position movement
     */
    public UUID getAccountId() { return accountId; }
    /**
     * Returns the instrument id associated with this position movement.
     * @return the UUID of the instrument associated with this position movement
     */
    public UUID getInstrumentId() { return instrumentId; }

    /**
     * Returns the order id associated with this position movement.
     * @return the UUID of the order associated with this position movement
     */
    public UUID getOrderId() { return orderId; }

    /**
     * Returns the execution id associated with this position movement.
     * @return the UUID of the execution associated with this position movement
     */
    public UUID getExecutionId() { return executionId; }

    /**
     * Returns the type of movement for this position movement.
     * @return the movement type (e.g., "BUY", "SELL")
     */
    public String getMovementType() { return movementType; }

    /**
     * Returns the change in quantity for this position movement.
     * @return the quantity delta
     */
    public long getQuantityDelta() { return quantityDelta; }

    /**
     * Returns the change in cost for this position movement.
     * @return the cost delta
     */
    public BigDecimal getCostDelta() { return costDelta; }

    /**
     * Returns the timestamp when this position movement was created.
     * @return the creation timestamp
     */
    public OffsetDateTime getCreatedAt() { return createdAt; }
}

