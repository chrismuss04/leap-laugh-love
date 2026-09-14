package com.leap.leaplaughlove.marketdata.api;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * Record to represent the latest simulated price for an instrument.
 * @param symbol the instrument symbol
 * @param price the latest simulated price
 * @param asOf the timestamp the price was generated
 */
public record PriceResponse(String symbol, BigDecimal price, OffsetDateTime asOf) {
}
