package com.leap.leaplaughlove.marketdata.history;

import com.leap.leaplaughlove.marketdata.instrument.SimulatedInstrument;
import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Represents one OHLC candle aggregated from the live simulation tick stream for an
 * instrument, over a fixed-width time bucket.
 */
@Entity
@Table(name = "price_candles", schema = "marketdata")
public class PriceCandle {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "candle_id")
    private UUID candleId;

    @ManyToOne
    @JoinColumn(name = "instrument_id", nullable = false)
    private SimulatedInstrument instrument;

    @Column(name = "bucket_start", nullable = false)
    private OffsetDateTime bucketStart;

    @Column(name = "bucket_seconds", nullable = false)
    private int bucketSeconds;

    @Column(name = "open", nullable = false, precision = 18, scale = 6)
    private BigDecimal open;

    @Column(name = "high", nullable = false, precision = 18, scale = 6)
    private BigDecimal high;

    @Column(name = "low", nullable = false, precision = 18, scale = 6)
    private BigDecimal low;

    @Column(name = "close", nullable = false, precision = 18, scale = 6)
    private BigDecimal close;

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
        this.instrument = instrument;
        this.bucketStart = bucketStart;
        this.bucketSeconds = bucketSeconds;
        this.open = open;
        this.high = high;
        this.low = low;
        this.close = close;
    }

    /**
     * Gets the unique identifier of the candle.
     * @return the candleId of the candle
     */
    public UUID getCandleId() { return candleId; }
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
}
