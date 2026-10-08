package com.leap.leaplaughlove.order.report;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * A filled order as the reporting read model holds it, with just what an activity report needs.
 * @param side BUY or SELL
 * @param quantity the quantity filled
 * @param fillPrice the price it filled at
 * @param completedAt when it filled
 */
public record ReportedFill(String side, long quantity, BigDecimal fillPrice, OffsetDateTime completedAt) {
}
