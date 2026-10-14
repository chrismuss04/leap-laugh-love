package com.leap.leaplaughlove.order.ops;

import java.time.OffsetDateTime;

/**
 * One step of a trade's timeline. For PRICED, {@code at} is when the quote was produced.
 */
public record TimelineStep(OffsetDateTime at, TimelineStepType type, String description) {
}
