package com.leap.leaplaughlove.account.position;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Represents a trading position of a specific account and instrument.  
 */
@Entity
@IdClass(PositionId.class)
@Table(name = "positions", schema = "trading")
public class Position {

    @Id
    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Id
    @Column(name = "instrument_id", nullable = false)
    private UUID instrumentId;

    @Column(name = "quantity", nullable = false)
    private long quantity;

    @Column(name = "avg_cost", nullable = false, precision = 18, scale = 6)
    private BigDecimal avgCost;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    /**
     * Protected no-argument constructor for JPA.
     */
    protected Position() {
    }

    /**
     * Constructs a new Position instance with the specified account ID, instrument ID, quantity, average cost, and update timestamp.
     * @param accountId the unique identifier of the account
     * @param instrumentId the unique identifier of the instrument
     * @param quantity the quantity of the position
     * @param avgCost the average cost of the position
     * @param updatedAt the timestamp when the position was last updated
     */
    public Position(UUID accountId, UUID instrumentId, long quantity, BigDecimal avgCost, OffsetDateTime updatedAt) {
        this.accountId = accountId;
        this.instrumentId = instrumentId;
        this.quantity = quantity;
        this.avgCost = avgCost != null ? avgCost : BigDecimal.ZERO;
        this.updatedAt = updatedAt != null ? updatedAt : OffsetDateTime.now();
    }

    /**
     * Returns the unique identifier of the account associated with this position.
     * @return the account ID
     */
    public UUID getAccountId() {
        return accountId;
    }

    /**
     * Returns the unique identifier of the instrument associated with this position.
     * @return the instrument ID
     */
    public UUID getInstrumentId() {
        return instrumentId;
    }

    /**
     * Returns the quantity of the position.
     * @return the quantity
     */
    public long getQuantity() {
        return quantity;
    }

    /**
     * Returns the average cost of the position.
     * @return the average cost
     */
    public BigDecimal getAvgCost() {
        return avgCost;
    }

    /**
     * Returns the timestamp when the position was last updated.
     * @return the update timestamp
     */
    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }

    /**
     * Sets the quantity of the position.
     * @param quantity the new quantity
     */
    public void setQuantity(long quantity) {
        this.quantity = quantity;
    }

    /**
     * Sets the average cost of the position.
     * @param avgCost the new average cost
     */
    public void setAvgCost(BigDecimal avgCost) {
        this.avgCost = avgCost;
    }

    /**
     * Sets the timestamp when the position was last updated.
     * @param updatedAt the new update timestamp
     */
    public void setUpdatedAt(OffsetDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}

