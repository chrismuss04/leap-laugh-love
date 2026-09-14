package com.leap.leaplaughlove.marketdata.api;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * Record to represent one OHLC candle in a price history response.
 * @param bucketStart the start of the candle's time bucket
 * @param open the opening price of the bucket
 * @param high the highest price of the bucket
 * @param low the lowest price of the bucket
 * @param close the closing price of the bucket
 */
public record PriceCandleResponse(
        OffsetDateTime bucketStart,
        BigDecimal open,
        BigDecimal high,
        BigDecimal low,
        BigDecimal close
) {
}
