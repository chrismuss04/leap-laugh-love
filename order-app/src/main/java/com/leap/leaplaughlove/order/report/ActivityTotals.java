package com.leap.leaplaughlove.order.report;

import java.math.BigDecimal;

/**
 * Trading activity totals over a report period or one of its buckets. Only FILLED orders count:
 * a rejected order never traded.
 * @param tradeCount the number of filled orders, buys and sells together
 * @param buyCount the number of filled buy orders
 * @param sellCount the number of filled sell orders
 * @param buyValue the total value bought, the sum of quantity times fill price
 * @param sellValue the total value sold, the sum of quantity times fill price
 * @param volume the total quantity traded, buys and sells together
 */
public record ActivityTotals(
        long tradeCount,
        long buyCount,
        long sellCount,
        BigDecimal buyValue,
        BigDecimal sellValue,
        long volume
) {
    /** Totals for a period with no trading activity. */
    public static final ActivityTotals EMPTY =
            new ActivityTotals(0, 0, 0, BigDecimal.ZERO, BigDecimal.ZERO, 0);
}
