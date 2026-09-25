package com.leap.leaplaughlove.marketdata.history;

import com.leap.leaplaughlove.marketdata.instrument.SimulatedInstrument;
import jakarta.persistence.*;
import org.springframework.data.domain.Persistable;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

/**
 * Represents one OHLC candle aggregated from the live simulation tick stream for an
 * instrument, over a fixed-width time bucket.
 *
 * <p>Identified by its natural key - instrument, width and bucket start - rather than a
 * surrogate id. The key is assigned, not generated, so the entity reports whether it is new
 * itself: otherwise Spring Data's {@code saveAll} would treat every candle as possibly existing
 * and merge it, issuing a SELECT per row and giving up the batched inserts the backfill and the
 * accumulator's flush depend on. Candles are only ever inserted, never updated.
 */
@Entity
@Table(name = "price_candles", schema = "marketdata")
@IdClass(PriceCandle.Key.class)
public class PriceCandle implements Persistable<PriceCandle.Key> {

    @Id
    @Column(name = "instrument_id", nullable = false)
    private UUID instrumentId;

    // Read-only: instrument_id is written through the key column above. A @ManyToOne in the key
    // itself makes Hibernate reject instruments loaded outside the saving transaction, which is
    // how both the backfill and the accumulator hold them.
    @ManyToOne
    @JoinColumn(name = "instrument_id", insertable = false, updatable = false)
    private SimulatedInstrument instrument;

    @Id
    @Column(name = "bucket_seconds", nullable = false)
    private int bucketSeconds;

    @Id
    @Column(name = "bucket_start", nullable = false)
    private OffsetDateTime bucketStart;

    @Column(name = "open", nullable = false, precision = 18, scale = 6)
    private BigDecimal open;

    @Column(name = "high", nullable = false, precision = 18, scale = 6)
    private BigDecimal high;

    @Column(name = "low", nullable = false, precision = 18, scale = 6)
    private BigDecimal low;

    @Column(name = "close", nullable = false, precision = 18, scale = 6)
    private BigDecimal close;

    @Transient
    private boolean isNew = true;

    protected PriceCandle() {
    }

    /**
     * Creates a new PriceCandle entity with the specified details.
     * @param instrument the instrument this candle is for
     * @param bucketStart the start of the candle's time bucket
     * @param bucketSeconds the width, in seconds, of the candle's time bucket
     * @param open the opening price of the bucket
     * @param high the highest price of the bucket
     * @param low the lowest price of the bucket
     * @param close the closing price of the bucket
     */
    public PriceCandle(SimulatedInstrument instrument, OffsetDateTime bucketStart, int bucketSeconds,
                        BigDecimal open, BigDecimal high, BigDecimal low, BigDecimal close) {
        this.instrumentId = instrument.getInstrumentId();
        this.instrument = instrument;
        this.bucketStart = bucketStart;
        this.bucketSeconds = bucketSeconds;
        this.open = open;
        this.high = high;
        this.low = low;
        this.close = close;
    }

    /**
     * Gets the candle's primary key.
     * @return the instrument, width and bucket start identifying the candle
     */
    @Override
    public Key getId() {
        return new Key(instrumentId, bucketSeconds, bucketStart);
    }

    /**
     * Reports whether this candle has not been stored yet, so saving it inserts it directly.
     * @return true until the candle has been persisted or was loaded from the database
     */
    @Override
    public boolean isNew() { return isNew; }

    @PostPersist
    @PostLoad
    void markNotNew() { this.isNew = false; }

    /**
     * Gets the instrument this candle is for.
     * @return the instrument of the candle
     */
    public SimulatedInstrument getInstrument() { return instrument; }
    /**
     * Gets the start of the candle's time bucket.
     * @return the bucketStart of the candle
     */
    public OffsetDateTime getBucketStart() { return bucketStart; }
    /**
     * Gets the width, in seconds, of the candle's time bucket.
     * @return the bucketSeconds of the candle
     */
    public int getBucketSeconds() { return bucketSeconds; }
    /**
     * Gets the opening price of the bucket.
     * @return the open price of the candle
     */
    public BigDecimal getOpen() { return open; }
    /**
     * Gets the highest price of the bucket.
     * @return the high price of the candle
     */
    public BigDecimal getHigh() { return high; }
    /**
     * Gets the lowest price of the bucket.
     * @return the low price of the candle
     */
    public BigDecimal getLow() { return low; }
    /**
     * Gets the closing price of the bucket.
     * @return the close price of the candle
     */
    public BigDecimal getClose() { return close; }

    /**
     * The composite primary key of a candle. Field names and types match the entity's
     * {@code @Id} attributes, as {@code @IdClass} requires.
     */
    public static class Key implements Serializable {

        private UUID instrumentId;
        private int bucketSeconds;
        private OffsetDateTime bucketStart;

        protected Key() {
        }

        /**
         * Creates a candle key.
         * @param instrumentId the id of the candle's instrument
         * @param bucketSeconds the width, in seconds, of the candle's time bucket
         * @param bucketStart the start of the candle's time bucket
         */
        public Key(UUID instrumentId, int bucketSeconds, OffsetDateTime bucketStart) {
            this.instrumentId = instrumentId;
            this.bucketSeconds = bucketSeconds;
            this.bucketStart = bucketStart;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof Key key)) {
                return false;
            }
            // Compared as instants: the same bucket can come back from the database at a
            // different offset than it was built with.
            return bucketSeconds == key.bucketSeconds
                    && Objects.equals(instrumentId, key.instrumentId)
                    && (bucketStart == null ? key.bucketStart == null
                        : key.bucketStart != null && bucketStart.isEqual(key.bucketStart));
        }

        @Override
        public int hashCode() {
            return Objects.hash(instrumentId, bucketSeconds, bucketStart == null ? null : bucketStart.toInstant());
        }
    }
}
