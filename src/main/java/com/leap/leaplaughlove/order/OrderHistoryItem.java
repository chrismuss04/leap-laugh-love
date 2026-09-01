package com.leap.leaplaughlove.order;

import java.time.OffsetDateTime;
import java.util.UUID;

// A simplified, client-facing summary of one order — kept separate from the Order entity
// so database internals aren't exposed directly through the API.
public class OrderHistoryItem {

    private final UUID orderId;
    private final String symbol;
    private final String side;
    private final long quantity;
    private final String status;
    private final OffsetDateTime submittedAt;
    private final OffsetDateTime filledAt;

    public OrderHistoryItem(UUID orderId, String symbol, String side, long quantity,
                             String status, OffsetDateTime submittedAt, OffsetDateTime filledAt) {
        this.orderId = orderId;
        this.symbol = symbol;
        this.side = side;
        this.quantity = quantity;
        this.status = status;
        this.submittedAt = submittedAt;
        this.filledAt = filledAt;
    }

    public UUID getOrderId() {
        return orderId;
    }

    public String getSymbol() {
        return symbol;
    }

    public String getSide() {
        return side;
    }

    public long getQuantity() {
        return quantity;
    }

    public String getStatus() {
        return status;
    }

    public OffsetDateTime getSubmittedAt() {
        return submittedAt;
    }

    public OffsetDateTime getFilledAt() {
        return filledAt;
    }
}
