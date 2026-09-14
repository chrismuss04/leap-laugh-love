package com.leap.leaplaughlove.marketdata.simulation;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * In-memory snapshot of the latest simulated price for an instrument, and the payload
 * published on {@link PriceTickEvent}.
 * @param symbol the instrument symbol
 * @param price the latest simulated price
 * @param asOf the timestamp the price was generated
 */
public record PriceState(String symbol, BigDecimal price, OffsetDateTime asOf) {
}
