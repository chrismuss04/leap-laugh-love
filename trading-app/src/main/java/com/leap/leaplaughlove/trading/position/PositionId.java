package com.leap.leaplaughlove.trading.position;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

public class PositionId implements Serializable {

    private UUID accountId;
    private UUID instrumentId;

    public PositionId() {
    }

    public PositionId(UUID accountId, UUID instrumentId) {
        this.accountId = accountId;
        this.instrumentId = instrumentId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof PositionId that)) {
            return false;
        }
        return Objects.equals(accountId, that.accountId)
                && Objects.equals(instrumentId, that.instrumentId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(accountId, instrumentId);
    }
}
