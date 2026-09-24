package com.leap.leaplaughlove.order.quote;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * Record to represent a current market quote fetched from the market data service, used to
 * price an order at execution time.
 */
public record QuoteSnapshot(
        String symbol,
        BigDecimal bidPrice,
        long bidSize,
        BigDecimal askPrice,
        long askSize,
        BigDecimal lastPrice,
        long lastSize,
        String exchange,
        OffsetDateTime quoteTimestamp
) {}

