package com.leap.leaplaughlove.trading.order;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

public record OrderHistoryItem(
        UUID orderId,
        String symbol,
        String side,
        BigDecimal quantity,
        String status,
        OffsetDateTime submittedAt,
        OffsetDateTime filledAt
) {}
