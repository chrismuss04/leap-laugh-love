package com.leap.leaplaughlove.marketdata.api;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record PriceResponse(String symbol, BigDecimal price, OffsetDateTime asOf) {
}
