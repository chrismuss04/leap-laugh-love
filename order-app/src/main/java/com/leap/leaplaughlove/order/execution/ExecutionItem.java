package com.leap.leaplaughlove.order.execution;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Execution item for transferring execution details between different layers of the application.
 * @param executionId the unique identifier of the execution
 * @param quantity the quantity filled in this execution
 * @param price the price at which the order was filled
 * @param executedAt the timestamp when the execution occurred
 */
public record ExecutionItem(
        UUID executionId,
        Long quantity,
        BigDecimal price,
        OffsetDateTime executedAt
) {}

