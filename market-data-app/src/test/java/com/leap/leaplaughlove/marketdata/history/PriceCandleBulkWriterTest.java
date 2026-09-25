package com.leap.leaplaughlove.marketdata.history;

import com.leap.leaplaughlove.marketdata.instrument.SimulatedInstrument;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DisplayName("PriceCandleBulkWriter Unit Tests")
class PriceCandleBulkWriterTest {

    @Test
    @DisplayName("renders candles as COPY text rows in the COPY statement's column order")
    void testRendersCopyTextRows() {
        UUID instrumentId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        SimulatedInstrument instrument = new SimulatedInstrument(
                instrumentId, "AAPL", "Apple Inc.",
                new BigDecimal("150.00"), new BigDecimal("0.07"), new BigDecimal("0.25"), 42L, true);
        // A non-UTC offset, to show the bucket start is written as the same instant in UTC.
        OffsetDateTime bucketStart = OffsetDateTime.of(2026, 9, 24, 21, 0, 0, 0, ZoneOffset.ofHours(-5));
        PriceCandle candle = new PriceCandle(instrument, bucketStart, 3600,
                new BigDecimal("150.100000"), new BigDecimal("151.000000"),
                new BigDecimal("1E+2").setScale(6), new BigDecimal("150.500000"));

        String text = PriceCandleBulkWriter.toCopyText(List.of(candle, candle));

        String row = "11111111-1111-1111-1111-111111111111\t3600\t2026-09-25T02:00:00Z\t"
                + "150.100000\t151.000000\t100.000000\t150.500000\n";
        assertEquals(row + row, text);
    }
}
