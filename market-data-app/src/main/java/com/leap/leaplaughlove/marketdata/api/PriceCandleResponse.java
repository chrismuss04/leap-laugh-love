package com.leap.leaplaughlove.marketdata.api;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * API representation of one OHLC candle.
 *
 * @param bucketStart the start of the candle's time bucket
 * @param bucketSeconds the width, in seconds, of the candle's time bucket - echoed back so a
 *     client can tell how much time a point covers without tracking what it requested
 * @param open the opening price of the bucket
 * @param high the highest price of the bucket
 * @param low the lowest price of the bucket
 * @param close the closing price of the bucket
 */
public record PriceCandleResponse(
        OffsetDateTime bucketStart,
        int bucketSeconds,
        BigDecimal open,
        BigDecimal high,
        BigDecimal low,
        BigDecimal close
) {
}
