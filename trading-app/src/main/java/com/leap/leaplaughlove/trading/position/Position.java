package com.leap.leaplaughlove.trading.position;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

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

    @Column(name = "avg_cost", nullable = false)
    private BigDecimal avgCost;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected Position() {
    }

    public UUID getAccountId() {
        return accountId;
    }

    public UUID getInstrumentId() {
        return instrumentId;
    }

    public long getQuantity() {
        return quantity;
    }

    public BigDecimal getAvgCost() {
        return avgCost;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }
}
