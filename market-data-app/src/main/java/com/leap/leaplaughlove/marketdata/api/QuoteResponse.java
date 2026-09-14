package com.leap.leaplaughlove.marketdata.api;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record QuoteResponse(String symbol, BigDecimal bidPrice, long bidSize, BigDecimal askPrice,
                             long askSize, BigDecimal lastPrice, long lastSize, String exchange,
                             OffsetDateTime quoteTimestamp) {
}
