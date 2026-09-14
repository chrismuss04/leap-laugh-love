package com.leap.leaplaughlove.marketdata.ingestion;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * A parsed, structurally-valid quote feed message. Field order mirrors the raw wire format:
 * symbol|bidPrice|bidSize|askPrice|askSize|lastPrice|lastSize|exchange|sequenceNumber|quoteTimestamp
 */
public record QuoteFeedMessage(String symbol, BigDecimal bidPrice, long bidSize, BigDecimal askPrice,
                                long askSize, BigDecimal lastPrice, long lastSize, String exchange,
                                long sequenceNumber, OffsetDateTime quoteTimestamp) {
}
