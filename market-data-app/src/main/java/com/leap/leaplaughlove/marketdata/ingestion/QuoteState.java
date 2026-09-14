package com.leap.leaplaughlove.marketdata.ingestion;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * In-memory snapshot of the latest accepted quote for a symbol, and the payload published
 * on {@link QuoteIngestedEvent}. Kept separate from the {@link Quote} JPA entity the same
 * way PriceState is kept separate from PriceCandle.
 * @param symbol the instrument symbol
 * @param bidPrice the bid price
 * @param bidSize the bid size
 * @param askPrice the ask price
 * @param askSize the ask size
 * @param lastPrice the last-traded price
 * @param lastSize the last-traded size
 * @param exchange the exchange the quote was sourced from
 * @param sequenceNumber the feed's sequence number for this quote
 * @param quoteTimestamp the timestamp assigned by the feed
 * @param receivedAt the timestamp this backend ingested the quote
 */
public record QuoteState(String symbol, BigDecimal bidPrice, long bidSize, BigDecimal askPrice,
                          long askSize, BigDecimal lastPrice, long lastSize, String exchange,
                          long sequenceNumber, OffsetDateTime quoteTimestamp, OffsetDateTime receivedAt) {
}
