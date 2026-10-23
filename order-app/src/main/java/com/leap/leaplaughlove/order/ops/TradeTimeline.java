package com.leap.leaplaughlove.order.ops;

import java.math.BigDecimal;
import java.util.List;

/**
 * A trade reconstructed step by step, oldest first. quotedPrice and maxSlippagePercent are null
 * when the order carried none.
 * @param trade the trade
 * @param quotedPrice the price quoted at submission, or null
 * @param maxSlippagePercent the slippage the client allowed, or null
 * @param steps the steps, oldest first
 */
public record TradeTimeline(
        TradeSummary trade,
        BigDecimal quotedPrice,
        BigDecimal maxSlippagePercent,
        List<TimelineStep> steps
) {
    /**
     * Copies the steps, treating null as none.
     * @param trade the trade
     * @param quotedPrice the price quoted at submission, or null
     * @param maxSlippagePercent the slippage the client allowed, or null
     * @param steps the steps, oldest first, or null for none
     */
    public TradeTimeline {
        steps = steps != null ? List.copyOf(steps) : List.of();
    }
}
