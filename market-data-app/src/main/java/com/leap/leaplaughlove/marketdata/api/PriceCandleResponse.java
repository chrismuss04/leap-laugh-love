package com.leap.leaplaughlove.marketdata.api;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record PriceCandleResponse(
        OffsetDateTime bucketStart,
        BigDecimal open,
        BigDecimal high,
        BigDecimal low,
        BigDecimal close
) {
}
