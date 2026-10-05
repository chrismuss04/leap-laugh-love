package com.leap.leaplaughlove.marketdata.history;

import com.leap.leaplaughlove.marketdata.api.PriceCandleResponse;
import com.leap.leaplaughlove.marketdata.instrument.SimulatedInstrument;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("PriceCandle Unit Tests")
class PriceCandleTest {

    private static final UUID INSTRUMENT_ID = UUID.randomUUID();
    private static final OffsetDateTime BUCKET = OffsetDateTime.parse("2026-01-01T10:00:00Z");

    private final SimulatedInstrument instrument = new SimulatedInstrument(INSTRUMENT_ID, "AAPL", "Apple Inc.",
            new BigDecimal("100"), new BigDecimal("0.05"), new BigDecimal("0.2"), 42L, true);

    private PriceCandle candle() {
        return new PriceCandle(instrument, BUCKET, 300, new BigDecimal("1"), new BigDecimal("4"),
                new BigDecimal("0.5"), new BigDecimal("3"));
    }

    @Test
    @DisplayName("exposes the values it was built with")
    void exposesValues() {
        PriceCandle candle = candle();

        assertSame(instrument, candle.getInstrument());
        assertEquals(BUCKET, candle.getBucketStart());
        assertEquals(300, candle.getBucketSeconds());
        assertEquals(new BigDecimal("1"), candle.getOpen());
        assertEquals(new BigDecimal("4"), candle.getHigh());
        assertEquals(new BigDecimal("0.5"), candle.getLow());
        assertEquals(new BigDecimal("3"), candle.getClose());
        assertEquals(new PriceCandle.Key(INSTRUMENT_ID, 300, BUCKET), candle.getId());
    }

    @Test
    @DisplayName("is new until it has been persisted or loaded")
    void isNewUntilPersisted() {
        PriceCandle candle = candle();
        assertTrue(candle.isNew());

        candle.markNotNew();

        assertFalse(candle.isNew());
    }

    @Test
    @DisplayName("keys are equal for the same instant at a different offset")
    void keysCompareInstants() {
        PriceCandle.Key key = new PriceCandle.Key(INSTRUMENT_ID, 300, BUCKET);
        PriceCandle.Key sameInstant = new PriceCandle.Key(INSTRUMENT_ID, 300,
                BUCKET.withOffsetSameInstant(ZoneOffset.ofHours(5)));

        assertEquals(key, key);
        assertEquals(key, sameInstant);
        assertEquals(key.hashCode(), sameInstant.hashCode());
    }

    @Test
    @DisplayName("keys differ by instrument, width, bucket start, or type")
    void keysDiffer() {
        PriceCandle.Key key = new PriceCandle.Key(INSTRUMENT_ID, 300, BUCKET);
        PriceCandle.Key noBucket = new PriceCandle.Key(INSTRUMENT_ID, 300, null);

        assertNotEquals(key, new PriceCandle.Key(UUID.randomUUID(), 300, BUCKET));
        assertNotEquals(key, new PriceCandle.Key(INSTRUMENT_ID, 60, BUCKET));
        assertNotEquals(key, new PriceCandle.Key(INSTRUMENT_ID, 300, BUCKET.plusMinutes(5)));
        assertNotEquals(key, noBucket);
        assertNotEquals(noBucket, key);
        assertEquals(noBucket, new PriceCandle.Key(INSTRUMENT_ID, 300, null));
        assertEquals(noBucket.hashCode(), new PriceCandle.Key(INSTRUMENT_ID, 300, null).hashCode());
        assertNotEquals(null, key);
        assertNotEquals("not a key", key);
    }

    @Test
    @DisplayName("the API response carries a candle's values")
    void responseCarriesValues() {
        PriceCandleResponse response = new PriceCandleResponse(BUCKET, 300, new BigDecimal("1"),
                new BigDecimal("4"), new BigDecimal("0.5"), new BigDecimal("3"));

        assertEquals(BUCKET, response.bucketStart());
        assertEquals(300, response.bucketSeconds());
        assertEquals(new BigDecimal("1"), response.open());
        assertEquals(new BigDecimal("4"), response.high());
        assertEquals(new BigDecimal("0.5"), response.low());
        assertEquals(new BigDecimal("3"), response.close());
    }
}
