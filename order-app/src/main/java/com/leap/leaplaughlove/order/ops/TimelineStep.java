package com.leap.leaplaughlove.order.ops;

import java.time.OffsetDateTime;

/**
 * One step of a trade's timeline. For PRICED, {@code at} is when the quote was produced.
 * @param at when the step happened
 * @param type the kind of step
 * @param description what happened, for display
 */
public record TimelineStep(OffsetDateTime at, TimelineStepType type, String description) {
}
