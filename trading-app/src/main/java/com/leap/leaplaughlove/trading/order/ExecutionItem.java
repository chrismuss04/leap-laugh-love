package com.leap.leaplaughlove.trading.order;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

public record ExecutionItem(
        UUID executionId,
        Long quantity,
        BigDecimal price,
        OffsetDateTime executedAt
) {}
