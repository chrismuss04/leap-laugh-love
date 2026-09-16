package com.leap.leaplaughlove.trading.quote;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * Record to represent a current market quote fetched from the market data service, used to
 * price an order at execution time.
 * @param symbol the instrument symbol
 * @param bidPrice the bid price
 * @param bidSize the bid size
 * @param askPrice the ask price
 * @param askSize the ask size
 * @param lastPrice the last-traded price
 * @param lastSize the last-traded size
 * @param exchange the exchange the quote was sourced from
 * @param quoteTimestamp the timestamp assigned by the feed, used to judge staleness
 */
public record QuoteSnapshot(String symbol, BigDecimal bidPrice, long bidSize, BigDecimal askPrice,
                            long askSize, BigDecimal lastPrice, long lastSize, String exchange,
                            OffsetDateTime quoteTimestamp) {
}
