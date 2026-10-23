package com.leap.leaplaughlove.order.quote;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * Record to represent a current market quote fetched from the market data service, used to
 * price an order at execution time.
 * @param symbol the instrument's symbol
 * @param bidPrice the best bid
 * @param bidSize the shares bid at the best bid
 * @param askPrice the best ask
 * @param askSize the shares offered at the best ask
 * @param lastPrice the last traded price
 * @param lastSize the shares in the last trade
 * @param exchange the exchange the quote came from
 * @param quoteTimestamp when the market data provider produced the quote
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

