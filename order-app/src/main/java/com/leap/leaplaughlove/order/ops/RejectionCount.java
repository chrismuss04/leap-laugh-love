package com.leap.leaplaughlove.order.ops;

import java.time.OffsetDateTime;

/**
 * How often orders were rejected for one reason over a period, and the most recent. reason is
 * null for rejections recorded without one.
 */
public record RejectionCount(
        String reason,
        long count,
        OffsetDateTime lastRejectedAt
) {
}
