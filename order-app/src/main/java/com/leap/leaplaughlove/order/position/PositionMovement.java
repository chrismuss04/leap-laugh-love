package com.leap.leaplaughlove.order.position;

import com.leap.leaplaughlove.common.persistence.BasePositionMovement;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Represents an entry in the position movements ledger (trading.position_movements).
 * An immutable audit record capturing changes to account holdings.
 */
@Entity
@Table(name = "position_movements", schema = "trading")
public class PositionMovement extends BasePositionMovement {

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
        super(accountId, instrumentId, orderId, executionId, movementType, quantityDelta, costDelta, createdAt);
    }
}
