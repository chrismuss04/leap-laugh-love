package com.leap.leaplaughlove.order.ops;

import java.math.BigDecimal;
import java.util.List;

/**
 * A trade reconstructed step by step, oldest first. quotedPrice and maxSlippagePercent are null
 * when the order carried none.
 */
public record TradeTimeline(
        TradeSummary trade,
        BigDecimal quotedPrice,
        BigDecimal maxSlippagePercent,
        List<TimelineStep> steps
) {
    public TradeTimeline {
        steps = steps != null ? List.copyOf(steps) : List.of();
    }
}
