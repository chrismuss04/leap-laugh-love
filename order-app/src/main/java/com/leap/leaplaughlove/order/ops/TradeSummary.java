package com.leap.leaplaughlove.order.ops;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A trade as a search result.
 */
public record TradeSummary(
        UUID orderId,
        OffsetDateTime submittedAt,
        String clientEmail,
        String accountNumber,
        String symbol,
        String side,
        long quantity,
        String status
) {
}
