package com.leap.leaplaughlove.order.ops;

import java.time.OffsetDateTime;

/**
 * How often orders were rejected for one reason over a period, and the most recent. reason is
 * null for rejections recorded without one.
 * @param reason the rejection reason, or null
 * @param count how many orders were rejected for it
 * @param lastRejectedAt when the most recent was rejected
 */
public record RejectionCount(
        String reason,
        long count,
        OffsetDateTime lastRejectedAt
) {
}
