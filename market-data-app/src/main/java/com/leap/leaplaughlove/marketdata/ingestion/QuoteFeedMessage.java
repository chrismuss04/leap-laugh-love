package com.leap.leaplaughlove.marketdata.ingestion;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * A parsed, structurally-valid quote feed message. Field order mirrors the raw wire format:
 * symbol|bidPrice|bidSize|askPrice|askSize|lastPrice|lastSize|exchange|sequenceNumber|quoteTimestamp
 * @param symbol the instrument symbol
 * @param bidPrice the bid price
 * @param bidSize the bid size
 * @param askPrice the ask price
 * @param askSize the ask size
 * @param lastPrice the last-traded price
 * @param lastSize the last-traded size
 * @param exchange the exchange the message was sourced from
 * @param sequenceNumber the feed's sequence number for this message
 * @param quoteTimestamp the timestamp assigned by the feed
 */
public record QuoteFeedMessage(String symbol, BigDecimal bidPrice, long bidSize, BigDecimal askPrice,
                                long askSize, BigDecimal lastPrice, long lastSize, String exchange,
                                long sequenceNumber, OffsetDateTime quoteTimestamp) {
}
