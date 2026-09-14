package com.leap.leaplaughlove.marketdata.simulation;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record PriceState(String symbol, BigDecimal price, OffsetDateTime asOf) {
}
