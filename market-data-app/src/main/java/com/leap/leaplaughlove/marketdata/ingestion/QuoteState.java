package com.leap.leaplaughlove.marketdata.ingestion;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * In-memory snapshot of the latest accepted quote for a symbol, and the payload published
 * on {@link QuoteIngestedEvent}. Kept separate from the {@link Quote} JPA entity the same
 * way PriceState is kept separate from PriceCandle.
 */
public record QuoteState(String symbol, BigDecimal bidPrice, long bidSize, BigDecimal askPrice,
                          long askSize, BigDecimal lastPrice, long lastSize, String exchange,
                          long sequenceNumber, OffsetDateTime quoteTimestamp, OffsetDateTime receivedAt) {
}
