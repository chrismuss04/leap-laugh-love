package com.leap.leaplaughlove.order.report;

import java.math.BigDecimal;

/**
 * The headline figures for one period of a commercial analyst's business insights. Trading
 * figures count FILLED orders only; a client is active when any of their orders completed in the
 * period, whatever its outcome.
 * @param tradedValue the total value traded, the sum of quantity times fill price
 * @param tradeCount the number of filled orders
 * @param averageOrderValue the traded value per filled order, or null when nothing traded
 * @param newRegistrations the number of clients who registered
 * @param activeClients the number of distinct clients with at least one completed order
 */
public record InsightFigures(
        BigDecimal tradedValue,
        long tradeCount,
        BigDecimal averageOrderValue,
        long newRegistrations,
        long activeClients
) {
}
