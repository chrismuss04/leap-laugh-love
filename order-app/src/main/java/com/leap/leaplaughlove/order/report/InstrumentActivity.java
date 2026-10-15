package com.leap.leaplaughlove.order.report;

import java.math.BigDecimal;

/**
 * How much one instrument traded over a period. Only FILLED orders count.
 * @param symbol the instrument's trading symbol
 * @param tradeCount the number of filled orders in the instrument
 * @param tradedValue the total value traded in the instrument
 */
public record InstrumentActivity(String symbol, long tradeCount, BigDecimal tradedValue) {
}
